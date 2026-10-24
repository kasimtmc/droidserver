package com.plyg.droidserver.core.metrics

import com.plyg.droidserver.core.config.StaticConfig
import com.plyg.droidserver.core.i18n.tr
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.util.AttributeKey

internal class MetricsSettings {
    lateinit var metrics: Metrics
    var basePath: String = "/api"
}

private val STARTED = AttributeKey<Long>("droidserver.metrics.started")

/**
 * Her isteğin süresini ve durumunu [Metrics]'e yazar; ilk kurulan eklenti olmalı (erken reddedilen istekler de sayılsın).
 * Başarılı metrik okumaları (`_metrics`; panel ve Prometheus düzenli yoklar) trafik sayılmaz, sayıları şişirmesin diye.
 */
internal val MetricsPlugin = createApplicationPlugin("DroidServerMetrics", ::MetricsSettings) {
    val metrics = pluginConfig.metrics
    val base = pluginConfig.basePath
    val metricsPath = base.trimEnd('/') + "/_metrics"
    onCall { call ->
        call.attributes.put(STARTED, System.nanoTime())
        metrics.started()
    }
    on(ResponseSent) { call ->
        val started = call.attributes.getOrNull(STARTED) ?: return@on
        val path = call.request.local.uri.substringBefore('?')
        val status = call.response.status()?.value ?: 0
        if (path == metricsPath && status in 200..299) {
            metrics.skipped()
            return@on
        }
        metrics.finished(
            call.request.local.method.value,
            if (StaticConfig.underPath(path, base)) path else tr("(statik site)"),
            status,
            (System.nanoTime() - started) / 1_000_000,
        )
    }
}
