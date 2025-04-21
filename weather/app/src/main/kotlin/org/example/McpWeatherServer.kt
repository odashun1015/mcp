package org.example

import io.ktor.client.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.streams.*
import io.modelcontextprotocol.kotlin.sdk.*
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import kotlinx.serialization.json.*

// Main function to run the MCP server
fun `run mcp server`() {
    // Base URL for the Weather API
    val baseUrl = "https://api.weather.gov"

    // Create an HTTP client with a default request configuration and JSON content negotiation
    val httpClient = HttpClient {
        defaultRequest {
            url(baseUrl)
            headers {
                append("Accept", "application/geo+json")
                append("User-Agent", "WeatherApiClient/1.0")
            }
            contentType(ContentType.Application.Json)
        }
        // Install content negotiation plugin for JSON serialization/deserialization
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
            })
        }
    }

    val server = Server(
        Implementation( // MCPサーバの名前とバージョンを定義
            name = "weather",
            version = "1.0.0"
        ),
        ServerOptions( // MCPサーバの設定オプション
            // listChanged=trueにすることでツール内容に変更が入った際に通知処理を行う
                capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = true))
        )
    )

    // Register a tool to fetch weather alerts by state
    server.addTool(
        name = "get_alerts", // ツール名
        description = """
            Get weather alerts for a US state. Input is Two-letter US state code (e.g. CA, NY)
        """.trimIndent(), // ツールの説明
        inputSchema = Tool.Input( // MCPクライアントから受け取る入力スキーマ（JSON）を定義する
            properties = buildJsonObject {
                putJsonObject("state") {
                    put("type", "string")
                    put("description", "Two-letter US state code (e.g. CA, NY)")
                }
            },
            required = listOf("state")
        )
    ) { request -> // ツールの実行処理を定義する
        val state = request.arguments["state"]?.jsonPrimitive?.content // リクエスト「state」パラメータを取得
        if (state == null) { // 「state」パラメータがnullの場合にエラーメッセージを返す
            return@addTool CallToolResult(
                content = listOf(TextContent("The 'state' parameter is required."))
            )
        }

        val alerts = httpClient.getAlerts(state) // APIから天気警報を取得

        CallToolResult(content = alerts.map { TextContent(it) }) // 取得した警報情報をMCPクライアントに返す
    }

    // Register a tool to fetch weather forecast by latitude and longitude
    server.addTool(
        name = "get_forecast",
        description = """
            Get weather forecast for a specific latitude/longitude
        """.trimIndent(),
        inputSchema = Tool.Input(
            properties = buildJsonObject {
                putJsonObject("latitude") {
                    put("type", "number")
                }
                putJsonObject("longitude") {
                    put("type", "number")
                }
            },
            required = listOf("latitude", "longitude")
        )
    ) { request ->
        val latitude = request.arguments["latitude"]?.jsonPrimitive?.doubleOrNull
        val longitude = request.arguments["longitude"]?.jsonPrimitive?.doubleOrNull
        if (latitude == null || longitude == null) {
            return@addTool CallToolResult(
                content = listOf(TextContent("The 'latitude' and 'longitude' parameters are required."))
            )
        }

        val forecast = httpClient.getForecast(latitude, longitude)

        CallToolResult(content = forecast.map { TextContent(it) })
    }

    // 標準入出力を使用してMCPサーバーと通信するためのトランスポートを作成
    val transport = StdioServerTransport(
        System.`in`.asInput(),
        System.out.asSink().buffered()
    )
    // MCPサーバーを起動し、クライアント接続から終了まで待機するロジック
    runBlocking {
        server.connect(transport)    // suspend関数でMCPクライアントとの双方向通信を開始
        val done = Job()             // サーバー終了検知用のJobオブジェクトを作成
        server.onClose {             // 接続が切断されたタイミングで、Jobを完了状態にする
            done.complete()
        }
        done.join()                  // Job完了（＝サーバー終了）までサスペンドして待機
    }
}