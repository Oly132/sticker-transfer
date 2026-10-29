package com.example.stickerpackbuilder.reverse

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ConcurrentHashMap

/** Prepare a bounded window before it is needed by TikTok. Each staged album
 * remains immutable and contains exactly one file. Native imports stay serial.
 */
internal class ReverseBatchPipeline<P, S : Any>(
    private val prepare: suspend (String) -> P,
    private val stage: suspend (String, P) -> S,
    private val import: suspend (String, S, () -> Unit) -> Unit,
    private val release: suspend (S) -> Unit,
    private val progress: (Int, Int, String) -> Unit = { _, _, _ -> },
    private val preparationFailed: ((String, Throwable) -> Unit)? = null,
    private val lookahead: Int = 3
) {
    init { require(lookahead in 1..5) }
    private class PreparationFailure(val original: Throwable) : Exception(original)
    suspend fun run(ids: List<String>): Int = supervisorScope {
        val staged=CopyOnWriteArrayList<S>()
        val submitted=ConcurrentHashMap.newKeySet<S>()
        val tasks=ArrayList<Deferred<S>>()
        val pending=linkedMapOf<Int,Deferred<S>>()
        val converters=Semaphore(2)
        val publisher=Semaphore(1)
        fun enqueue(index:Int){
            if(index !in ids.indices||index in pending)return
            val task=async {
                val prepared=try{converters.withPermit{prepare(ids[index])}}
                catch(cancel:CancellationException){throw cancel}
                catch(error:Exception){throw PreparationFailure(error)}
                publisher.withPermit {
                    // Track even a stage completed concurrently with cancellation.
                    withContext(NonCancellable){stage(ids[index],prepared).also{staged.add(it)}}
                }
            }
            pending[index]=task;tasks.add(task)
        }
        repeat(minOf(lookahead,ids.size)){enqueue(it)}
        var completed=0
        try {
            for((index,id) in ids.withIndex()){
                currentCoroutineContext().ensureActive()
                val current=try{pending.remove(index)!!.await()}
                catch(error:PreparationFailure){
                    val failed=preparationFailed?:throw error.original
                    failed(id,error.original);enqueue(index+lookahead);continue
                }
                currentCoroutineContext().ensureActive()
                import(id,current){submitted.add(current)}
                submitted.remove(current)
                if(runCatching{release(current)}.isSuccess)staged.remove(current)
                completed++;progress(completed,ids.size,id)
                enqueue(index+lookahead)
            }
            completed
        }finally{
            withContext(NonCancellable){
                tasks.forEach{if(!it.isCompleted)it.cancel()}
                tasks.joinAll()
                // Never remove an upload whose Create result is still uncertain.
                staged.filterNot{it in submitted}.forEach{runCatching{release(it)}}
            }
        }
    }
}
