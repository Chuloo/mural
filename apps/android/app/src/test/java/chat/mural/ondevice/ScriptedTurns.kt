package chat.mural.ondevice

/**
 * One scripted Model Runtime behind the atomic turn seam: every Helper and voice conversation test
 * drives the fake this way, exactly as [OnDeviceModelController] drives the real one.
 */
internal fun scriptedTurn(runtime: ScriptedModelRuntime, model: String = "models/gemma-4-e4b.litertlm") =
    ModelTurn { systemPrompt, request, onPartial ->
        val session = runtime.load(model)
        session.restart(systemPrompt)
        session.respond(request, onPartial)
    }
