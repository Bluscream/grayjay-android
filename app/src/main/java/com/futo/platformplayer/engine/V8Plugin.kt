package com.futo.platformplayer.engine

import android.content.Context
import android.os.Looper
import com.caoccao.javet.exceptions.JavetCompilationException
import com.caoccao.javet.exceptions.JavetExecutionException
import com.caoccao.javet.interop.V8Host
import com.caoccao.javet.interop.V8Runtime
import com.caoccao.javet.values.V8Value
import com.caoccao.javet.values.primitive.V8ValueBoolean
import com.caoccao.javet.values.primitive.V8ValueInteger
import com.caoccao.javet.values.primitive.V8ValueString
import com.caoccao.javet.values.reference.V8ValueObject
import com.futo.platformplayer.*
import com.futo.platformplayer.api.http.ManagedHttpClient
import com.futo.platformplayer.constructs.Event1
import com.futo.platformplayer.engine.exceptions.*
import com.futo.platformplayer.engine.internal.V8Converter
import com.futo.platformplayer.engine.packages.*
import com.futo.platformplayer.logging.Logger
import com.futo.platformplayer.states.StateApp
import com.futo.platformplayer.states.StateAssets
import kotlinx.coroutines.*

class V8Plugin {
    val config: IV8PluginConfig;
    private val _client: ManagedHttpClient;
    private val _clientAuth: ManagedHttpClient;


    val httpClient: ManagedHttpClient get() = _client;
    val httpClientAuth: ManagedHttpClient get() = _clientAuth;

    var _runtime : V8Runtime? = null;

    private val _deps : LinkedHashMap<String, String> = LinkedHashMap();
    private val _depsPackages : MutableList<V8Package> = mutableListOf();
    private var _script : String? = null;

    val onStopped = Event1<V8Plugin>();

    constructor(context: Context, config: IV8PluginConfig, script: String? = null, client: ManagedHttpClient = ManagedHttpClient(), clientAuth: ManagedHttpClient = ManagedHttpClient()) {
        this._client = client;
        this._clientAuth = clientAuth;
        this.config = config;
        this._script = script;
        withDependency(PackageBridge(this, config));

        for(pack in config.packages)
            withDependency(getPackage(context, pack));
    }

    fun withDependency(context: Context, assetPath: String) : V8Plugin  {
        if(!_deps.containsKey(assetPath))
            _deps.put(assetPath, getAssetFile(context, assetPath));
        return this;
    }
    fun withDependency(name: String, script: String) : V8Plugin {
        if(!_deps.containsKey(name))
            _deps.put(name, script);
        return this;
    }
    fun withDependency(v8Package: V8Package) : V8Plugin {
        _depsPackages.add(v8Package);
        return this;
    }
    fun withScript(script: String) : V8Plugin {
        _script = script;
        return this;
    }

    fun getPackages(): List<V8Package> {
        return _depsPackages.toList();
    }
    fun getPackageVariables(): List<String> {
        return _depsPackages.filter { it.variableName != null }.map { it.variableName!! }.toList();
    }
    fun getPackageByVariableName(varName: String): V8Package? {
        return _depsPackages.firstOrNull { it.variableName == varName };
    }

    fun start() {
        val script = _script ?: throw IllegalStateException("Attempted to start V8 without script");
        synchronized(this) {
            if (_runtime != null)
                return;

            val host = V8Host.getV8Instance();
            val options = host.jsRuntimeType.getRuntimeOptions();
            _runtime = host.createV8Runtime(options);
            if (!host.isIsolateCreated)
                throw IllegalStateException("Isolate not created");

            //Setup bridge
            _runtime?.let {
                it.converter = V8Converter();

                for (pack in _depsPackages) {
                    if (pack.variableName != null)
                        it.createV8ValueObject().use { v8valueObject ->
                            it.globalObject.set(pack.variableName, v8valueObject);
                            v8valueObject.bind(pack);
                        };
                    catchScriptErrors("Package Dep[${pack.name}]") {
                        for (packScript in pack.getScripts())
                            it.getExecutor(packScript).executeVoid();
                    }
                }

                //Load deps
                for (dep in _deps)
                    catchScriptErrors("Dep[${dep.key}]") {
                        it.getExecutor(dep.value).executeVoid()
                    };


                if (config.allowEval)
                    it.allowEval(true);

                //Load plugin
                catchScriptErrors("Plugin[${config.name}]") {
                    it.getExecutor(script).executeVoid()
                };
            }
        }
    }
    fun stop(){
        Logger.i(TAG, "Stopping plugin [${config.name}]");
        synchronized(this) {
            _runtime?.let {
                _runtime = null;
                if(!it.isClosed && !it.isDead)
                    it.close();
            };
        }
        onStopped.emit(this);
    }

    fun execute(js: String) : V8Value {
        return executeTyped<V8Value>(js);
    }
    fun <T : V8Value> executeTyped(js: String) : T {
        warnIfMainThread("V8Plugin.executeTyped");

        val runtime = _runtime ?: throw IllegalStateException("JSPlugin not started yet");
        return catchScriptErrors("Plugin[${config.name}]", js) { runtime.getExecutor(js).execute() };
    }
    fun executeBoolean(js: String) : Boolean? = catchScriptErrors("Plugin[${config.name}]") { executeTyped<V8ValueBoolean>(js).value };
    fun executeString(js: String) : String? = catchScriptErrors("Plugin[${config.name}]") { executeTyped<V8ValueString>(js).value };
    fun executeInteger(js: String) : Int? = catchScriptErrors("Plugin[${config.name}]") { executeTyped<V8ValueInteger>(js).value };

    private fun getPackage(context: Context, packageName: String): V8Package {
        //TODO: Auto get all package types?
        return when(packageName) {
            "DOMParser" -> PackageDOMParser(context, this)
            "Http" -> PackageHttp(this, config)
            "Utilities" -> PackageUtilities(this, config)
            else -> throw ScriptCompilationException(config, "Unknown package [${packageName}] required for plugin ${config.name}");
        };
    }

    fun <T : Any> catchScriptErrors(context: String, code: String? = null, handle: ()->T): T {
        return catchScriptErrors(this.config, context, code, handle);
    }

    companion object {
        private val REGEX_EX_FALLBACK = Regex(".*throw.*?[\"](.*)[\"].*");
        private val REGEX_EX_FALLBACK2 = Regex(".*throw.*?['](.*)['].*");

        val TAG = "V8Plugin";

        fun <T: Any?> catchScriptErrors(config: IV8PluginConfig, context: String, code: String? = null, handle: ()->T): T {
            var codeStripped = code;
            if(codeStripped != null) { //TODO: Improve code stripped
                if (codeStripped.contains("(") && codeStripped.contains(")"))
                {
                    val start = codeStripped.indexOf("(");
                    val end = codeStripped.lastIndexOf(")");
                    codeStripped = codeStripped.substring(0, start) + "(...)" + codeStripped.substring(end + 1);
                }
            }
            try {
                val result = handle();

                if(result is V8ValueObject) {
                    val type = result.getString("plugin_type");
                    if(type != null && type.endsWith("Exception"))
                        Companion.throwExceptionFromV8(
                            config,
                            result.getOrThrow(config, "plugin_type", "V8Plugin"),
                            result.getOrThrow(config, "message", "V8Plugin"),
                            null,
                            null,
                            codeStripped
                        );
                }


                return result;
            }
            catch(scriptEx: JavetCompilationException) {
                throw ScriptCompilationException(config, "Compilation: ${scriptEx.message}\n(${scriptEx.scriptingError.lineNumber})[${scriptEx.scriptingError.startColumn}-${scriptEx.scriptingError.endColumn}]: ${scriptEx.scriptingError.sourceLine}", null, codeStripped);
            }
            catch(executeEx: JavetExecutionException) {
                val exMessage = extractJSExceptionMessage(executeEx);

                if(executeEx.scriptingError?.context?.containsKey("plugin_type") == true)
                    throwExceptionFromV8(
                        config,
                        executeEx.scriptingError.context["plugin_type"].toString(),
                        (exMessage ?: ""),
                        executeEx,
                        executeEx.scriptingError?.stack,
                        codeStripped
                    );

                throw ScriptExecutionException(config, "${exMessage}", null, executeEx.scriptingError?.stack, codeStripped);
            }
            catch(ex: Exception) {
                throw ex;
            }
        }

        private fun throwExceptionFromV8(config: IV8PluginConfig, pluginType: String, msg: String, innerEx: Exception? = null, stack: String? = null, code: String? = null) {
            when(pluginType) {
                "ScriptException" -> throw ScriptException(config, msg, innerEx, stack, code);
                "AgeException" -> throw ScriptAgeException(config, msg, innerEx, stack, code);
                "UnavailableException" -> throw ScriptUnavailableException(config, msg, innerEx, stack, code);
                "ScriptExecutionException" -> throw ScriptExecutionException(config, msg, innerEx, stack, code);
                "ScriptCompilationException" -> throw ScriptCompilationException(config, msg, innerEx, code);
                "ScriptImplementationException" -> throw ScriptImplementationException(config, msg, innerEx, null, code);
                "ScriptTimeoutException" -> throw ScriptTimeoutException(config, msg, innerEx);
                "NoInternetException" -> throw NoInternetException(config, msg, innerEx, stack, code);
                else -> throw ScriptExecutionException(config, msg, innerEx, stack, code);
            }
        }

        private fun extractJSExceptionMessage(ex: JavetExecutionException) : String? {
            val lineInfo = " (${ex.scriptingError.lineNumber})[${ex.scriptingError.startColumn}-${ex.scriptingError.endColumn}]";

            if(ex.message == null || ex.message == "<null>") {
                if(!ex.scriptingError?.message.isNullOrEmpty())
                    return ex.scriptingError?.message!! + lineInfo;
                else if(!ex.scriptingError?.sourceLine?.isNullOrEmpty()!!) {
                    val source = ex.scriptingError.sourceLine;
                    val matchReg1 = REGEX_EX_FALLBACK.matchEntire(source);
                    val matchReg2 = REGEX_EX_FALLBACK2.matchEntire(source);
                    if(matchReg1 != null)
                        return matchReg1.groupValues[1] + lineInfo;
                    if(matchReg2 != null)
                        return matchReg2.groupValues[1] + lineInfo;
                }
            }
            else if(!ex.scriptingError?.detailedMessage.isNullOrEmpty())
                return ex.scriptingError.detailedMessage + lineInfo;
            else if(!ex.scriptingError?.message.isNullOrEmpty())
                return ex.scriptingError.message + lineInfo;
            return ex.message + lineInfo;
        }

        private fun getAssetFile(context: Context, path: String) : String {
            return StateAssets.readAsset(context, path) ?: throw java.lang.IllegalStateException("script ${path} not found");
        }
    }


    /**
     * Methods available for scripts (bridge object)
     */
}