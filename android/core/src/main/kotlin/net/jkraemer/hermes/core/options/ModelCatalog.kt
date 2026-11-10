package net.jkraemer.hermes.core.options

/** A model as Hermes addresses it: the [provider]'s slug and the [model] id. */
data class ModelRef(val provider: String, val model: String)
