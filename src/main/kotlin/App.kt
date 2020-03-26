import com.fasterxml.jackson.databind.ObjectMapper
import khttp.post
import org.zeromq.SocketType
import org.zeromq.ZContext
import org.zeromq.ZMQ
import java.math.BigDecimal
import java.net.URLDecoder
import java.sql.Connection
import java.sql.DriverManager

val mapper = ObjectMapper()
val optionalDataHandlers = emptyList<OptionalDataHandler>()

fun main() {
    val user = System.getProperty("db.user")
    val password = System.getProperty("db.password")
    val connection = DriverManager.getConnection("jdbc:postgresql://localhost/device?user=$user&password=$password")

    ZContext().use { context ->
        val router: ZMQ.Socket = context.createSocket(SocketType.ROUTER).apply {
            bind("tcp://*:5556")
        }

        while (!Thread.currentThread().isInterrupted) {
            val id = router.recv() // ZeroMQ router id
            val empty = router.recv() // empty frame
            val payload = router.recvStr()

            val (gateway, taintedParams, taintedMessage) = stripAndParseHeader(payload)
            val firstParam = taintedParams.firstOrNull()
            val message = taintedMessage.trim()
            val possibleConfigurations = findConfigurationsForGateway(connection, gateway)
            val (matchingConfiguration, fields) =
                possibleConfigurations
                .map { configuration -> configuration to message.split(*configuration.separators.toCharArray()) }
                .first { (configuration, fields) ->
                    // in that case end here otherwise we would get the indices would be out of bounds
                    if (configuration.fieldCount != fields.filter { it.isNotEmpty() }.size) {
                        return@first false
                    }

                    val handleMatches = message.matches(configuration.messageHandleRegex.toRegex())
                    val idLength = if (configuration.idRegex.isNotEmpty()) {
                        getCapturedValue(fields[configuration.idIdx], configuration.idRegex) { it.length }
                    } else {
                        fields[configuration.idIdx].length
                    }

                    handleMatches || idLength == configuration.idLength
                }

            if (optionalDataHandlers.isNotEmpty()) {
                optionalDataHandlers
                .first { it.canHandle(matchingConfiguration.name) }
                .execute(matchingConfiguration.optionalData)
            }

            when (matchingConfiguration.sendType) {
                "Emergency" -> forwardEmergency(matchingConfiguration, fields)
            }
        }
    }
}

fun <T> getCapturedValue(field: String, pattern: String, transform: (String) -> T): T {
    return transform(pattern.toRegex().find(URLDecoder.decode(field, "UTF-8"))!!.groupValues[1])
}

fun stripAndParseHeader(payload: String): Triple<String, List<String>, String> {
    val (transport, taintedParams, _, message) =
        """\[\[<([^>]+)>((?:,<([^>]+)>)*)\]\](.*)"""
        .toRegex()
        .find(payload)!!
        .destructured
    // drop is a safe operation even for empty lists
    val params = taintedParams.split(",").drop(1)
    return Triple(transport, params, message)
}

fun forwardEmergency(configuration: Configuration, fields: List<String>) {
    val latitude = if (configuration.latitudeRegex.isNotEmpty()) {
        getCapturedValue(fields[configuration.latitudeIdx], configuration.latitudeRegex) { it.toBigDecimal() }
    } else {
        fields[configuration.latitudeIdx].toBigDecimal()
    }

    val longitude = if (configuration.longitudeRegex.isNotEmpty()) {
        getCapturedValue(fields[configuration.longitudeIdx], configuration.longitudeRegex) { it.toBigDecimal() }
    } else {
        fields[configuration.longitudeIdx].toBigDecimal()
    }

    Emergency(
        deviceId = fields[configuration.idIdx],
        coordinate = Coordinate(
            latitude,
            longitude
        )
    ).also {
        println("sending emergency...")
        post("http://localhost:8080/emergency", data = mapper.writeValueAsString(it))
    }
}

fun findConfigurationsForGateway(connection: Connection, gateway: String): List<Configuration> {
    val configurations = mutableListOf<Configuration>()

    val fetchGateways = connection.prepareStatement("SELECT * FROM configuration WHERE transport = ?::\"transport\" OR transport = '*'")
    fetchGateways.setString(1, gateway)
    fetchGateways.executeQuery().use { rs ->
        while (rs.next()) {
            configurations += Configuration(
                name = rs.getString(1),
                separators = rs.getString(2),
                idIdx = rs.getInt(3),
                idLength = rs.getInt(4),
                idRegex = rs.getString(5),
                fieldCount = rs.getInt(6),
                sendType = rs.getString(7),
                transport = rs.getString(8),
                replyChannel = rs.getString(9),
                messageHandleRegex = rs.getString(10),
                latitudeIdx = rs.getInt(11),
                latitudeRegex = rs.getString(12),
                longitudeIdx= rs.getInt(13),
                longitudeRegex = rs.getString(14),
                isDMS = rs.getBoolean(15),
                description = rs.getString(16),
                optionalData = rs.getString(17)
            )
        }
    }

    return configurations
}

data class Emergency(
    val deviceId: String,
    val coordinate: Coordinate
)

data class Coordinate(
    val latitude: BigDecimal,
    val longitude: BigDecimal
)

data class Configuration(
    val name: String,
    val separators: String,
    val idIdx: Int,
    val idLength: Int,
    val idRegex: String,
    val sendType: String,
    val transport: String,
    val replyChannel: String,
    val messageHandleRegex: String,
    val latitudeIdx: Int,
    val latitudeRegex: String,
    val longitudeIdx: Int,
    val longitudeRegex: String,
    val isDMS: Boolean,
    val fieldCount: Int,
    val description: String,
    val optionalData: String
)