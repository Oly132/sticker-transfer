package com.example.stickerpackbuilder.reverse

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class ReverseBatchPipelineTest {
    @Test fun aDamagedOriginalDoesNotStopTheOtherSelectionsOrMiscountTheirIds() = runBlocking {
        val imported=mutableListOf<String>();val failed=mutableListOf<String>();val progress=mutableListOf<String>()
        val pipeline=ReverseBatchPipeline<String,String>(
            prepare={id->if(id=="bad")error("Invalid WebP container");id},
            stage={id,_->id},import={id,_,submitted->imported+=id;submitted()},release={},
            progress={count,total,id->progress+="$count/$total:$id"},
            preparationFailed={id,_->failed+=id}
        )
        assertEquals(2,pipeline.run(listOf("a","bad","c")))
        assertEquals(listOf("a","c"),imported)
        assertEquals(listOf("bad"),failed)
        assertEquals(listOf("1/3:a","2/3:c"),progress)
    }

    @Test fun theNextAlbumsAreIndexedBeforeCreateButTheCurrentFileIsRetained() = runBlocking {
        val events=mutableListOf<String>()
        val nextPrepared=CompletableDeferred<Unit>()
        val nextStaged=CompletableDeferred<Unit>()
        var active=0
        val pipeline=ReverseBatchPipeline<String,String>(
            prepare={ id -> events+="prepare:$id";if(id=="b")nextPrepared.complete(Unit);id },
            stage={ id,_ -> events+="stage:$id";if(id=="b")nextStaged.complete(Unit);id },
            import={ id,_,submitted ->
                assertEquals(1,++active);events+="import:$id"
                if(id=="a"){
                    withTimeout(5000){nextPrepared.await()}
                    withTimeout(5000){nextStaged.await()}
                    submitted()
                    assertFalse(events.contains("release:a"))
                    yield()
                    assertTrue(events.contains("prepare:c"))
                }else submitted()
                events+="confirmed:$id";active--
            },
            release={id -> events+="release:$id"}
        )
        assertEquals(3,pipeline.run(listOf("a","b","c")))
        assertTrue(events.indexOf("prepare:b")<events.indexOf("confirmed:a"))
        assertTrue(events.indexOf("stage:b")<events.indexOf("confirmed:a"))
        assertTrue(events.indexOf("release:a")>events.indexOf("confirmed:a"))
    }
    @Test fun cancellationPreservesSubmittedFileAndRemovesOnlyUnusedAheadFile() = runBlocking {
        val staged=mutableListOf<String>();val released=mutableListOf<String>()
        val aheadReady=CompletableDeferred<Unit>()
        val currentSubmitted=CompletableDeferred<Unit>()
        val pipeline=ReverseBatchPipeline<String,String>(
            prepare={it},stage={id,_->staged+=id;if(id=="b")aheadReady.complete(Unit);id},
            import={_,_,submitted->submitted();currentSubmitted.complete(Unit);awaitCancellation()},release={released+=it},lookahead=2
        )
        val job=launch{pipeline.run(listOf("a","b","c"))}
        withTimeout(5000){aheadReady.await();currentSubmitted.await()};job.cancelAndJoin()
        assertEquals(listOf("a","b"),staged)
        assertEquals(listOf("b"),released)
    }
    @Test fun aFailedNextConversionDoesNotCancelAnAlreadySubmittedCurrentImport() = runBlocking {
        val events=mutableListOf<String>();val failedNext=CompletableDeferred<Unit>()
        val pipeline=ReverseBatchPipeline<String,String>(
            prepare={id->if(id=="b"){failedNext.complete(Unit);error("Unsupported animation")};id},
            stage={id,_->id},
            import={id,_,submitted->submitted();withTimeout(5000){failedNext.await()};events+="confirmed:$id"},
            release={events+="release:$it"}
        )
        try{pipeline.run(listOf("a","b"));fail("Expected the next conversion failure")}
        catch(error:IllegalStateException){assertEquals("Unsupported animation",error.message)}
        assertEquals(listOf("confirmed:a","release:a"),events)
    }
    @Test fun preparationWindowIsBoundedAndCannotStartTwoNativeImports() = runBlocking {
        val staged=mutableListOf<String>();val importEntered=CompletableDeferred<Unit>()
        val allowFirst=CompletableDeferred<Unit>();var active=0;var maximum=0
        val pipeline=ReverseBatchPipeline<String,String>(
            prepare={it},stage={id,_->staged+=id;id},
            import={id,_,submitted->
                active++;maximum=maxOf(maximum,active);submitted()
                if(id=="a"){importEntered.complete(Unit);allowFirst.await()}
                active--
            },release={},lookahead=3
        )
        val job=async{pipeline.run(listOf("a","b","c","d","e"))}
        withTimeout(5000){importEntered.await()};yield()
        assertEquals(setOf("a","b","c"),staged.toSet())
        assertEquals(1,maximum)
        allowFirst.complete(Unit)
        assertEquals(5,job.await());assertEquals(1,maximum)
    }
    @Test fun cancellationTracksAFilePublishedInsideANonCancellableStage() = runBlocking {
        val publishing=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val released=mutableListOf<String>()
        val pipeline=ReverseBatchPipeline<String,String>(
            prepare={it},stage={id,_->publishing.complete(Unit);finish.await();id},
            import={_,_,_->fail("Cancelled staging must not import")},release={released+=it},lookahead=1
        )
        val job=launch{pipeline.run(listOf("a"))}
        withTimeout(5000){publishing.await()};job.cancel();finish.complete(Unit);job.join()
        assertEquals(listOf("a"),released)
    }
    @Test fun aDuplicateSkippedWithoutCreateDoesNotBlockTheSuccessor() = runBlocking {
        val pipeline=ReverseBatchPipeline<String,String>(prepare={it},stage={id,_->id},import={_,_,_->},release={})
        assertEquals(2,withTimeout(5000){pipeline.run(listOf("a","b"))})
    }
}
