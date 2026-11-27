package com.clipshelf.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton

/** Native vector artwork from the approved Ink & Coral reference. */
internal class CoralWave(context: Context, private val dark: Boolean): View(context) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val w=width.toFloat(); val h=height.toFloat()
        if(w<=0||h<=0) return
        val path=Path().apply {
            moveTo(0f,h*.74f); cubicTo(w*.18f,h*.99f,w*.34f,h*.21f,w*.56f,h*.49f)
            cubicTo(w*.76f,h*.86f,w*.79f,-h*.16f,w,h*.08f)
            lineTo(w,h*.66f); cubicTo(w*.80f,h*.14f,w*.74f,h*.87f,w*.55f,h*.59f)
            cubicTo(w*.32f,h*.30f,w*.18f,h*.98f,0f,h*.84f); close()
        }
        paint.shader=LinearGradient(0f,h,w,0f,
            intArrayOf(0x00FF8075,0x50FF8075,0xFFFF8075.toInt(),0xFFB63F36.toInt()),
            floatArrayOf(0f,.40f,.80f,1f),Shader.TileMode.CLAMP)
        c.drawPath(path,paint); paint.shader=null
        paint.style=Paint.Style.STROKE; paint.strokeWidth=1f
        paint.color=if(dark)0x30FF8075 else 0x48FF8075
        c.drawPath(path,paint); paint.style=Paint.Style.FILL
    }
}

/** Radio-list quality control, instead of a default system dropdown. */
internal class QualityRows(context: Context, options: List<String>, initial: Int, private val palette: Palette): LinearLayout(context) {
    private val rows=ArrayList<RadioButton>()
    private var listener: (() -> Unit)?=null
    var selectedItemPosition=initial.coerceIn(0,options.lastIndex)
        private set
    init {
        orientation=VERTICAL
        options.forEachIndexed { index,text ->
            val radio=RadioButton(context).apply {
                this.text=text; textSize=14f; gravity=Gravity.CENTER_VERTICAL
                setTextColor(palette.ink); buttonTintList=ColorStateList.valueOf(palette.accent)
                setPadding(Brand.dp(context,12),0,Brand.dp(context,12),0)
                minHeight=Brand.dp(context,44); minimumHeight=Brand.dp(context,44)
                setOnClickListener { selectedItemPosition=index; redraw(); listener?.invoke() }
            }
            rows.add(radio); addView(radio,LayoutParams(-1,Brand.dp(context,44)).apply { bottomMargin=Brand.dp(context,5) })
        }
        redraw()
    }
    private fun redraw() {
        rows.forEachIndexed { i,v ->
            val selected=i==selectedItemPosition
            v.isChecked=selected
            v.background=RippleDrawable(ColorStateList.valueOf(0x22FF8075),
                Brand.surface(context,if(selected) (if(Color.luminance(palette.background)<.45f) 0xFF402D30.toInt() else 0xFFFFE6E2.toInt()) else palette.panel2,
                    12,if(selected) palette.accent else palette.line),null)
        }
    }
    override fun setEnabled(value:Boolean) { super.setEnabled(value); rows.forEach { it.isEnabled=value; it.alpha=if(value)1f else .45f } }
    fun onSelect(action:()->Unit){listener=action}
}
