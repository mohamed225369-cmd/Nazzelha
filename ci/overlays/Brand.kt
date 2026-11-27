package com.clipshelf.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.widget.Button
import android.widget.TextView

internal object Brand {
    fun dp(context: Context, n: Int) = (context.resources.displayMetrics.density * n + .5f).toInt()
    fun palette(context: Context): Palette {
        val typed = android.util.TypedValue()
        context.theme.resolveAttribute(android.R.attr.colorBackground, typed, true)
        return if (Color.luminance(typed.data) < .45f) NazzelhaColors.dark else NazzelhaColors.light
    }
    fun surface(context: Context, color: Int, radius: Int = 20, stroke: Int? = null) =
        GradientDrawable().apply { setColor(color); cornerRadius = dp(context,radius).toFloat(); stroke?.let { setStroke(dp(context,1),it) } }
    fun icon(context: Context, name: String, color: Int, size: Int = 20): Drawable = object : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeWidth=1.8f; strokeCap=Paint.Cap.ROUND; strokeJoin=Paint.Join.ROUND; style=Paint.Style.STROKE }
        override fun draw(c: Canvas) {
            val saved=c.save()
            c.translate(bounds.left.toFloat(),bounds.top.toFloat()); c.scale(bounds.width()/24f,bounds.height()/24f)
            fun path(vararg xy: Float) {
                val p=Path().apply { moveTo(xy[0],xy[1]); for(i in 2 until xy.size step 2) lineTo(xy[i],xy[i+1]) }
                c.drawPath(p,paint)
            }
            when(name) {
                "download" -> { path(12f,3f,12f,16f); path(7f,11f,12f,16f,17f,11f); path(4f,16f,4f,21f,20f,21f,20f,16f) }
                "home" -> { path(3f,11f,12f,3f,21f,11f); path(5f,10f,5f,21f,10f,21f,10f,15f,14f,15f,14f,21f,19f,21f,19f,10f) }
                "settings" -> { c.drawCircle(12f,12f,4f,paint); c.drawCircle(12f,12f,8f,paint); for(i in 0..7){ val s=c.save();c.rotate(i*45f,12f,12f);path(12f,2f,12f,4f);c.restoreToCount(s) } }
                "paste" -> { c.drawRoundRect(6f,5f,19f,21f,2f,2f,paint); c.drawRoundRect(9f,2f,16f,7f,2f,2f,paint) }
                "edit" -> { path(4f,16f,16f,4f,20f,8f,8f,20f,4f,20f,4f,16f);path(13f,7f,17f,11f) }
                "refresh" -> { c.drawArc(4f,4f,20f,20f,-40f,285f,false,paint);path(20f,3f,20f,9f,14f,9f) }
                "close" -> { path(6f,6f,18f,18f);path(18f,6f,6f,18f) }
                "check" -> path(5f,12f,10f,17f,20f,7f)
                "play" -> path(8f,4f,20f,12f,8f,20f,8f,4f)
                "info" -> { c.drawCircle(12f,12f,9f,paint);path(12f,11f,12f,17f);c.drawPoint(12f,7f,paint) }
                "arrow" -> path(9f,5f,16f,12f,9f,19f)
                "video" -> { c.drawRoundRect(3f,5f,15f,19f,2f,2f,paint);path(15f,10f,21f,7f,21f,17f,15f,14f) }
                "folder" -> path(3f,7f,10f,7f,12f,10f,21f,10f,21f,20f,3f,20f,3f,7f)
                "more" -> { paint.style=Paint.Style.FILL; for(y in listOf(5f,12f,19f)) c.drawCircle(12f,y,1.6f,paint);paint.style=Paint.Style.STROKE }
                "link" -> { c.drawRoundRect(3f,8f,14f,16f,4f,4f,paint);c.drawRoundRect(10f,8f,21f,16f,4f,4f,paint);path(9f,12f,15f,12f) }
                "layers" -> {path(3f,8f,12f,3f,21f,8f,12f,13f,3f,8f);path(3f,12f,12f,17f,21f,12f);path(3f,16f,12f,21f,21f,16f)}
                "share" -> { c.drawCircle(5f,12f,2.5f,paint);c.drawCircle(19f,5f,2.5f,paint);c.drawCircle(19f,19f,2.5f,paint);path(7f,11f,17f,6f);path(7f,13f,17f,18f) }
                "bell" -> {path(5f,17f,7f,14f,7f,8f,9f,5f,15f,5f,17f,8f,17f,14f,19f,17f,5f,17f);path(10f,21f,14f,21f)}
                "audio" -> { path(10f,18f,10f,5f,20f,3f,20f,15f);c.drawOval(4f,15f,10f,21f,paint);c.drawOval(14f,12f,20f,18f,paint) }
                else -> { c.drawCircle(12f,12f,9f,paint);path(7f,12f,17f,12f) }
            }
            c.restoreToCount(saved)
        }
        override fun setAlpha(alpha: Int){paint.alpha=alpha;invalidateSelf()}
        override fun setColorFilter(filter: ColorFilter?){paint.colorFilter=filter;invalidateSelf()}
        @Deprecated("Deprecated in Android") override fun getOpacity()=PixelFormat.TRANSLUCENT
        override fun getIntrinsicWidth()=dp(context,size)
        override fun getIntrinsicHeight()=dp(context,size)
    }
    fun primary(context: Context, p: Palette, radius: Int = 16): Drawable =
        GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(p.accent, if(Color.luminance(p.background)<.45f) 0xFFFF665C.toInt() else 0xFF9B302A.toInt())).apply { cornerRadius=dp(context,radius).toFloat() }
    fun buttonIcon(button: Button, name: String, color: Int, size: Int = 18) {
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon(button.context,name,color,size),null,null,null)
        button.compoundDrawablePadding=dp(button.context,7)
    }
    fun iconFor(text: String): String? = when {
        text in listOf("تحميل","بدء التحميل","فتح الملف") || text.startsWith("تحميل ") -> "download"
        text == "الرئيسية" -> "home"
        text == "الإعدادات" -> "settings"
        text == "الملفات" -> "folder"
        text == "التحميلات" -> "download"
        text == "لصق" -> "paste"
        text == "تعديل" -> "edit"
        text == "مشاركة" -> "share"
        text == "إلغاء" || text == "إلغاء التحميل" -> "close"
        text == "إعادة المحاولة" || text.contains("تحديث") || text.contains("محرك") -> "refresh"
        text == "تفاصيل الخطأ" -> "info"
        text.startsWith("Downloads") || text.startsWith("Download") && !text.contains("engine",true) -> "download"
        text == "Add media" || text == "Back to library" -> "home"
        text == "Settings" -> "settings"
        text == "Paste" -> "paste"
        text == "Edit" -> "edit"
        text == "Retry" || text.contains("update",true) || text.contains("engine",true) -> "refresh"
        text.startsWith("Cancel") -> "close"
        text == "Open file" -> "play"
        text == "Error details" -> "info"
        else -> null
    }
    fun styleDialog(dialog: AlertDialog) {
        val p=palette(dialog.context)
        dialog.window?.setBackgroundDrawable(surface(dialog.context,p.panel,24,p.line))
        dialog.window?.setDimAmount(.62f)
        for (id in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(id)?.apply {
                isAllCaps=false
                typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL)
                setTextColor(if(id==AlertDialog.BUTTON_POSITIVE)p.onAccent else p.ink)
                background=RippleDrawable(ColorStateList.valueOf(0x22000000),surface(context,if(id==AlertDialog.BUTTON_POSITIVE)p.accent else p.panel2,14),null)
                minHeight=dp(context,44)
            }
        }
        dialog.listView?.apply { divider=null; dividerHeight=0 }
        dialog.findViewById<TextView>(android.R.id.message)?.apply { setTextColor(p.ink); textSize=14f }
    }
}
internal fun AlertDialog.Builder.showBranded(): AlertDialog = show().also(Brand::styleDialog)
internal fun AlertDialog.showBranded(): AlertDialog { show(); Brand.styleDialog(this); return this }

/** Standard user-facing errors remain short; original logs can be copied from diagnostics. */
internal object DisplayText {
    fun clean(text: String): String = text.replace(Regex("(?i)yt-dlp|ffmpeg|quickjs|python"),"download engine")
    fun updateError(): String = "Could not update. Try again."
}
