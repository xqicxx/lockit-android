package com.lockit.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Receiver for CodingPlanWidget.
 * Handles widget lifecycle events (add, update, delete).
 */
class CodingPlanWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = CodingPlanWidget()
}