/*
 * Copyright © 2025-2026 Virtu VPN (vcs.virtucomputing.com). All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * This file is part of Virtu VPN, based on wireguard-android
 * by WireGuard LLC, licensed under Apache-2.0.
 */
package com.wireguard.android.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import java.util.ArrayDeque

class SparklineView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private val maxPoints = 60
    private val rxPoints = ArrayDeque<Long>(maxPoints)
    private val txPoints = ArrayDeque<Long>(maxPoints)

    private val rxLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF00BFA5.toInt()
    }
    private val txLinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = 0xFF5EDBC2.toInt()
    }
    private val rxFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val txFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        color = 0x20FFFFFF
    }

    private val rxPath = Path()
    private val txPath = Path()
    private val rxFillPath = Path()
    private val txFillPath = Path()

    fun addDataPoint(rxBytesPerSec: Long, txBytesPerSec: Long) {
        if (rxPoints.size >= maxPoints) rxPoints.removeFirst()
        if (txPoints.size >= maxPoints) txPoints.removeFirst()
        rxPoints.addLast(rxBytesPerSec)
        txPoints.addLast(txBytesPerSec)
        invalidate()
    }

    fun clear() {
        rxPoints.clear()
        txPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        // Grid lines
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }

        if (rxPoints.isEmpty()) return

        val allMax = maxOf(
            rxPoints.maxOrNull() ?: 0L,
            txPoints.maxOrNull() ?: 0L,
            1024L // minimum scale 1 KB/s
        )

        drawLine(canvas, rxPoints, allMax, w, h, rxLinePaint, rxFillPaint, 0xFF00BFA5.toInt())
        drawLine(canvas, txPoints, allMax, w, h, txLinePaint, txFillPaint, 0xFF5EDBC2.toInt())
    }

    private fun drawLine(canvas: Canvas, points: ArrayDeque<Long>, max: Long, w: Float, h: Float, linePaint: Paint, fillPaint: Paint, color: Int) {
        if (points.size < 2) return
        val path = Path()
        val fillPath = Path()
        val step = w / (maxPoints - 1).toFloat()
        val offset = (maxPoints - points.size) * step

        fillPaint.shader = LinearGradient(0f, 0f, 0f, h, (color and 0x00FFFFFF) or 0x40000000, 0x00000000, Shader.TileMode.CLAMP)

        var first = true
        points.forEachIndexed { i, value ->
            val x = offset + i * step
            val y = h - (value.toFloat() / max * h * 0.85f)
            if (first) {
                path.moveTo(x, y)
                fillPath.moveTo(x, h)
                fillPath.lineTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
                fillPath.lineTo(x, y)
            }
        }
        fillPath.lineTo(offset + (points.size - 1) * step, h)
        fillPath.close()

        canvas.drawPath(fillPath, fillPaint)
        canvas.drawPath(path, linePaint)
    }
}
