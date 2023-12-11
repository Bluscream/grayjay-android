@file:Suppress("DEPRECATION")

package com.futo.platformplayer.api.media.platforms.js.models.sources

import com.caoccao.javet.values.V8Value
import com.caoccao.javet.values.reference.V8ValueObject
import com.futo.platformplayer.api.media.models.streams.sources.IAudioSource
import com.futo.platformplayer.api.media.models.streams.sources.IVideoSource
import com.futo.platformplayer.api.media.platforms.js.JSClient
import com.futo.platformplayer.api.media.platforms.js.models.JSRequestModifier
import com.futo.platformplayer.engine.IV8PluginConfig
import com.futo.platformplayer.engine.V8Plugin
import com.futo.platformplayer.orNull
import com.futo.platformplayer.views.video.datasources.JSHttpDataSource
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource
import com.google.android.exoplayer2.upstream.HttpDataSource

abstract class JSSource {
    protected val _plugin: JSClient;
    protected val _config: IV8PluginConfig;
    protected val _obj: V8ValueObject;
    val hasRequestModifier: Boolean;

    val type : String;

    constructor(type: String, plugin: JSClient, obj: V8ValueObject) {
        this._plugin = plugin;
        this._config = plugin.config;
        this._obj = obj;
        this.type = type;

        hasRequestModifier = obj.has("getRequestModifier");
    }

    fun getRequestModifier(): JSRequestModifier? {
        if (!hasRequestModifier || _obj.isClosed) {
            return null;
        }

        val result = V8Plugin.catchScriptErrors<Any>(_config, "[${_config.name}] JSVideoUrlSource", "obj.getRequestModifier()") {
            _obj.invoke("getRequestModifier", arrayOf<Any>());
        };

        if (result !is V8ValueObject) {
            return null;
        }

        return JSRequestModifier(_plugin, result)
    }

    companion object {
        const val TYPE_AUDIOURL = "AudioUrlSource";
        const val TYPE_VIDEOURL = "VideoUrlSource";
        const val TYPE_AUDIO_WITH_METADATA = "AudioUrlRangeSource";
        const val TYPE_VIDEO_WITH_METADATA = "VideoUrlRangeSource";
        const val TYPE_DASH = "DashSource";
        const val TYPE_HLS = "HLSSource";

        fun fromV8VideoNullable(plugin: JSClient, obj: V8Value?) : IVideoSource? = obj.orNull { fromV8Video(plugin, it as V8ValueObject) };
        fun fromV8Video(plugin: JSClient, obj: V8ValueObject) : IVideoSource {
            val type = obj.getString("plugin_type");
            return when(type) {
                TYPE_VIDEOURL -> JSVideoUrlSource(plugin, obj);
                TYPE_VIDEO_WITH_METADATA -> JSVideoUrlRangeSource(plugin, obj);
                TYPE_HLS -> fromV8HLS(plugin, obj);
                TYPE_DASH -> fromV8Dash(plugin, obj);
                else -> throw NotImplementedError("Unknown type ${type}");
            }
        }
        fun fromV8DashNullable(plugin: JSClient, obj: V8Value?) : JSDashManifestSource? = obj.orNull { fromV8Dash(plugin, it as V8ValueObject) };
        fun fromV8Dash(plugin: JSClient, obj: V8ValueObject) : JSDashManifestSource = JSDashManifestSource(plugin, obj);
        fun fromV8HLSNullable(plugin: JSClient, obj: V8Value?) : JSHLSManifestSource? = obj.orNull { fromV8HLS(plugin, it as V8ValueObject) };
        fun fromV8HLS(plugin: JSClient, obj: V8ValueObject) : JSHLSManifestSource = JSHLSManifestSource(plugin, obj);

        fun fromV8Audio(plugin: JSClient, obj: V8ValueObject) : IAudioSource {
            val type = obj.getString("plugin_type");
            return when(type) {
                TYPE_HLS -> JSHLSManifestAudioSource.fromV8HLS(plugin, obj);
                TYPE_AUDIOURL -> JSAudioUrlSource(plugin, obj);
                TYPE_AUDIO_WITH_METADATA -> JSAudioUrlRangeSource(plugin, obj);
                else -> throw NotImplementedError("Unknown type ${type}");
            }
        }
    }
}