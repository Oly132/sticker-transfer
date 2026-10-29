package com.example.stickerpackbuilder.reverse

internal object ImportControlPolicy {
    fun creationSurface(createTops:List<Int>,closeTops:List<Int>,height:Int,posts:Boolean,saved:Boolean):Boolean {
        if(height<=0)return false
        val title=posts&&createTops.any{it>=0&&it<height*.18}
        val editor=!posts&&!saved&&createTops.any{it>height*.65&&it<height}&&closeTops.any{it>=0&&it<height*.18}
        return title||editor
    }
    fun albumListEntry(label:String,left:Int,width:Int)=
        (label=="Recents"||label.matches(Regex("RSL_[a-f0-9]{12}")))&&albumRow(left,width)

    /** A dropdown row spans the picker width; its sliding header does not.
     * Text-left alone briefly misclassifies Recents during the entrance animation. */
    fun albumMenuRow(label:String,textLeft:Int,rowLeft:Int,rowRight:Int,width:Int)=
        albumListEntry(label,textLeft,width)&&rowLeft>=0&&rowLeft<width*.15&&
            rowRight<=width&&rowRight>=width*.85

    /** A verified one-file album has one file after TikTok's optional Capture cell. */
    fun fileCell(width:Int,height:Int,headerBottom:Int,camera:List<Int>?):Pair<Float,Float>? {
        if(width<=0||height<=0||headerBottom<=0)return null
        val point=if(camera==null)width/6f to headerBottom+width/6f else {
            if(camera.size!=4)return null
            val (left,top,right,bottom)=camera
            val cellWidth=right-left;val cellHeight=bottom-top
            if(left !in 0..(width*.08).toInt()||top<headerBottom||
                cellWidth !in (width*.28).toInt()..(width*.36).toInt()||
                cellHeight !in (cellWidth*.8).toInt()..(cellWidth*1.2).toInt())return null
            (right+cellWidth/2f) to (top+cellHeight/2f)
        }
        return point.takeIf{it.first in 0f..width.toFloat()&&it.second>headerBottom&&it.second<height-80}
    }

    fun touchInside(left:Int,top:Int,right:Int,bottom:Int,width:Int,height:Int)=
        left>=0&&top>=0&&right>left&&bottom>top&&right<=width&&bottom<=height
    fun albumRow(textLeft:Int,screenWidth:Int) = screenWidth>0&&textLeft>=screenWidth*.2
    fun createButton(label:String,top:Int,bottom:Int,screenHeight:Int,editorTools:Boolean,picker:Boolean,verifiedAnimatedEditor:Boolean=false) =
        label=="Create sticker"&&screenHeight>=400&&top>=screenHeight*.65&&bottom>top&&bottom<=screenHeight&&(editorTools||verifiedAnimatedEditor)&&!picker
}
