interface OptionalDataHandler {
    fun canHandle(name: String): Boolean
    fun execute(json: String)
}



