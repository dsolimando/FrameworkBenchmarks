package com.hexagonkt

import com.hexagonkt.helpers.Environment
import com.hexagonkt.settings.SettingsManager.setting
import com.mongodb.MongoClient
import com.mongodb.MongoClientURI

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource

import java.sql.Connection
import java.sql.ResultSet.CONCUR_READ_ONLY
import java.sql.ResultSet.TYPE_FORWARD_ONLY
import kotlin.reflect.KClass

import kotlin.reflect.KProperty1

internal const val WORLD_ROWS = 10000

private val worldName: String = defaultSetting("worldCollection", "world")
private val fortuneName: String = defaultSetting("fortuneCollection", "fortune")
private val databaseName = defaultSetting("database", "hello_world")

internal fun <T : Any> defaultSetting(name: String, value: T): T = setting(name) ?: value

private fun getDbUrl(engine: String): String {
    val dbHost = Environment.systemSetting("${engine.toUpperCase()}_DB_HOST", "localhost")

    return when (engine) {
        "mongodb" -> "mongodb://$dbHost/$databaseName"
        "postgresql" -> "jdbc:postgresql://$dbHost/$databaseName?" +
            "jdbcCompliantTruncation=false&" +
            "elideSetAutoCommits=true&" +
            "useLocalSessionState=true&" +
            "cachePrepStmts=true&" +
            "cacheCallableStmts=true&" +
            "alwaysSendSetIsolation=false&" +
            "prepStmtCacheSize=4096&" +
            "cacheServerConfiguration=true&" +
            "prepStmtCacheSqlLimit=2048&" +
            "traceProtocol=false&" +
            "useUnbufferedInput=false&" +
            "useReadAheadInput=false&" +
            "maintainTimeStats=false&" +
            "useServerPrepStmts=true&" +
            "cacheRSMetadata=true"
        else -> error("Unsupported database")
    }
}

internal fun createStore(engine: String): Store = when (engine) {
    "mongodb" -> MongoDbStore(getDbUrl(engine))
    "postgresql" -> SqlStore(getDbUrl(engine))
    else -> error("Unsupported database")
}

internal interface Store {
    fun findAllFortunes(): List<Fortune>
    fun findWorlds(count: Int): List<World>
    fun replaceWorlds(count: Int): List<World>
    fun close()
}

private class MongoDbStore(dbUrl: String) : Store {
    private val database by lazy { MongoClient(MongoClientURI(dbUrl)).getDatabase(databaseName) }

    private val worldRepository by lazy { repository(worldName, World::class, World::_id) }
    private val fortuneRepository by lazy { repository(fortuneName, Fortune::class, Fortune::_id) }

    private fun <T : Any> repository(name: String, type: KClass<T>, key: KProperty1<T, Int>) =
        com.hexagonkt.store.mongodb.MongoDbStore(type, key, name, database)

    override fun close() { /* Not needed */ }

    override fun findAllFortunes() = fortuneRepository.findAll()

    override fun findWorlds(count: Int) =
        (1..count).mapNotNull { worldRepository.findOne(randomWorld()) }

    override fun replaceWorlds(count: Int) = (1..count)
        .map { worldRepository.findOne(randomWorld())?.copy(randomNumber = randomWorld()) }
        .toList()
        .filterNotNull()
        .map {
            worldRepository.replaceOne(it)
            it
        }
}

private class SqlStore(jdbcUrl: String) : Store {
    companion object {
        private const val SELECT_WORLD = "select * from world where id = ?"
        private const val UPDATE_WORLD = "update world set randomNumber = ? where id = ?"
        private const val SELECT_ALL_FORTUNES = "select * from fortune"
    }

    private val dataSource: HikariDataSource by lazy {
        val config = HikariConfig()
        config.jdbcUrl = jdbcUrl
        config.maximumPoolSize = defaultSetting("maximumPoolSize", 16)
        config.username = defaultSetting("databaseUsername", "benchmarkdbuser")
        config.password = defaultSetting("databasePassword", "benchmarkdbpass")
        HikariDataSource(config)
    }

    override fun close() {
        dataSource.close()
    }

    override fun findAllFortunes(): List<Fortune> {
        var fortunes = listOf<Fortune>()

        dataSource.connection.use { con: Connection ->
            val rs = con.prepareStatement(SELECT_ALL_FORTUNES).executeQuery()
            while (rs.next())
                fortunes += Fortune(rs.getInt(1), rs.getString(2))
        }

        return fortunes
    }

    override fun findWorlds(count: Int): List<World> {
        var worlds: List<World> = listOf()

        dataSource.connection.use { con: Connection ->
            val stmtSelect = con.prepareStatement(SELECT_WORLD)

            for (ii in 0 until count) {
                stmtSelect.setInt(1, randomWorld())
                val rs = stmtSelect.executeQuery()
                rs.next()
                val id = rs.getInt(1)
                worlds += World(id, id, rs.getInt(2))
            }
        }

        return worlds
    }

    override fun replaceWorlds(count: Int): List<World> {
        var worlds: List<World> = listOf()

        dataSource.connection.use { con: Connection ->
            val stmtSelect = con.prepareStatement(SELECT_WORLD, TYPE_FORWARD_ONLY, CONCUR_READ_ONLY)
            val stmtUpdate = con.prepareStatement(UPDATE_WORLD)

            for (ii in 0 until count) {
                stmtSelect.setInt(1, randomWorld())
                val rs = stmtSelect.executeQuery()
                rs.next()

                val id = rs.getInt(1)
                val world = World(id, id, rs.getInt(2)).copy(randomNumber = randomWorld())
                worlds += world

                stmtUpdate.setInt(1, world.randomNumber)
                stmtUpdate.setInt(2, world.id)
                stmtUpdate.addBatch()

                if (ii % 25 == 0)
                    stmtUpdate.executeBatch()
            }

            stmtUpdate.executeBatch()
        }

        return worlds
    }
}
