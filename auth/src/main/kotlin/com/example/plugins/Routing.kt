package com.example.plugins

import com.auth0.jwk.JwkProviderBuilder
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.example.domain.entity.User
import com.example.domain.entity.UserToken
import com.example.domain.entity.UserToken.Companion.DAY_MILLIS
import com.example.domain.entity.YandexPrincipal
import com.example.domain.entity.YandexUser
import com.example.server.LocalApi
import com.example.server.response.AuthResponse
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.auth.jwt.*
import io.ktor.server.http.content.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.util.*
import java.util.concurrent.TimeUnit

fun Application.configureRouting(applicationHttpClient: HttpClient) {
    routing {
        // Применяю OAuth авторизацию к запросам ниже
        authenticate("auth-oauth-yandex") {
            // Используется клиентом, чтобы зарегистрироваться/войти
            // Далее он будет жить на jwt
            // Если jwt иссякнет, то опять /login
            get("/authorize") {
                // Редирект на страницу Яндекса
            }
        }
        get("/token") {
            checkInternalServerError(call) {
                // Получаю код из параметра
                val code = call.parameters["code"] ?: return@get call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(
                        message = "BadRequest: id не предоставлен"
                    )
                )
                val httpResponse = applicationHttpClient.post("https://oauth.yandex.ru/token") {
                    headers {
                        append(
                            HttpHeaders.Authorization, "Basic ${
                                Base64.getEncoder().encodeToString(
                                    "${System.getenv()["YANDEX_CLIENT_ID"]}:${System.getenv()["YANDEX_CLIENT_SECRET"]}".toByteArray()
                                )
                            }"
                        )
                        append(HttpHeaders.ContentType, ContentType.Application.FormUrlEncoded)
                    }
                    val parameters = Parameters.build {
                        append("grant_type", "authorization_code")
                        append("code", code)
                    }
                    setBody(
                        TextContent(parameters.formUrlEncode(), ContentType.Application.FormUrlEncoded)
                    )
                }
                if (httpResponse.status != HttpStatusCode.OK) {
                    return@get call.respond(
                        HttpStatusCode.BadRequest,
                        AuthResponse(
                            message = "BadRequest: code не валиден"
                        )
                    )
                }

                val principal: YandexPrincipal = httpResponse.body()

                // Заправшиваю информацию от Яндекса

                val httpResponseUser = applicationHttpClient.get("https://login.yandex.ru/info?format=json") {
                    headers {
                        append(HttpHeaders.Authorization, "Bearer ${principal.accessToken}")
                    }
                }
                val userInfo: YandexUser = httpResponseUser.body()

                val user = LocalApi.getWithYandex(userInfo.id.toLong())
                val resultId: Long =
                    if (user == null) {
                        // Регистрация
                        LocalApi.addUser(
                            User(
                                login = userInfo.login,
                                displayName = userInfo.displayName,
                                yandexId = userInfo.id.toLong(),
                                accessTokenYandex = principal.accessToken,
                                refreshTokenYandex = principal.refreshToken
                            )
                        )!!.id
                    } else {
                        // Авторизация
                        LocalApi.updateUser(
                            user.copy(
                                login = userInfo.login,
                                displayName = userInfo.displayName,
                                accessTokenYandex = principal.accessToken,
                                refreshTokenYandex = principal.refreshToken
                            )
                        )!!.id
                    }
                // Получение id нового утсройства
                val deviceId = LocalApi.getNewDeviceIdForUser(resultId)
                val tokenPair = generateJWT(this@configureRouting.environment, resultId, deviceId,userInfo.login,userInfo.displayName)
                val addedUserToken = LocalApi.addUserToken(
                    UserToken(
                        deviceId = deviceId,
                        id = resultId,
                        accessToken = tokenPair.accessToken,
                        refreshToken = tokenPair.refreshToken,
                        refreshTokenExpireAt = System.currentTimeMillis() + DAY_MILLIS,
                        login = userInfo.login,
                        displayName = userInfo.displayName
                    )
                )
                addedUserToken ?: throw IOException()
                return@get call.respond(
                    AuthResponse(
                        data = tokenPair
                    )
                )
            }
        }
        post("/refresh") {
            checkInternalServerError(call) {
                val oldRefreshToken = call.receiveNullable<RefreshToken>()
                val refreshToken = oldRefreshToken?.refreshToken ?: return@post call.respond(
                    HttpStatusCode.BadRequest,
                    AuthResponse(
                        message = "BadRequest: refresh_token не предоставлен"
                    )
                )
                val userToken = LocalApi.getUserTokenWithRefreshToken(refreshToken)
                userToken ?: return@post call.respond(
                    HttpStatusCode.NotFound,
                    AuthResponse(
                        message = "NotFound: Элемент с указанным refresh_token не существует"
                    )
                )
                if (userToken.refreshTokenExpireAt > System.currentTimeMillis()) {
                    // Токен действителен
                    val tokenPair = generateJWT(this@configureRouting.environment, userToken.id, userToken.deviceId,userToken.login,userToken.displayName)
                    LocalApi.updateUserToken(
                        UserToken(
                            deviceId = userToken.deviceId,
                            id = userToken.id,
                            accessToken = tokenPair.accessToken,
                            refreshToken = tokenPair.refreshToken,
                            refreshTokenExpireAt = tokenPair.expiresAt,
                            login = userToken.login,
                            displayName = userToken.displayName
                        )
                    )!!
                    return@post call.respond(
                        AuthResponse(
                            data = tokenPair
                        )
                    )
                } else {
                    return@post call.respond(
                        HttpStatusCode.BadRequest,
                        AuthResponse(
                            message = "BadRequest: токен просрочен"
                        )
                    )
                }
            }
        }
        // Делаю папку certs статикой
        static(".well-known") {
            staticRootFolder = File("certs")
            file("jwks.json")
        }
    }
}

fun generateJWT(environment: ApplicationEnvironment, userId: Long, deviceId: Long, login: String, displayName: String): TokenPair {
    // Приватный ключ RSA
    val privateKeyString = environment.config.property("jwt.access.privateKey").getString()
    // Изготовитель токена
    val issuer = environment.config.property("jwt.issuer").getString()
    // Для кого предназначен токен
    val audience = environment.config.property("jwt.audience").getString()
    // Время жизни access токена в минутах
    val accessLifeTime = environment.config.property("jwt.access.lifetime").getString().toInt()

    val refreshLifeTime = environment.config.property("jwt.refresh.lifetime").getString().toInt()
    // Получаю доступ к JWKS, который хранит публичные ключи
    // jwks.json является статическим файлом, чтобы другие пользователи могли использовать эти публичные ключи,
    // для проверки токена на валидность
    // указываю URI, где находится .well-known/jwks.json

    val jwkProvider = JwkProviderBuilder(issuer)
        .cached(10, 24, TimeUnit.HOURS)
        .rateLimited(10, 1, TimeUnit.MINUTES)
        .build()

    val publicKey = jwkProvider.get("test_key").publicKey
    val keySpecPKCS8 = PKCS8EncodedKeySpec(Base64.getDecoder().decode(privateKeyString))
    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(keySpecPKCS8)
    val accessExpiresAt = Date(System.currentTimeMillis() + 1000 * 60 * accessLifeTime)
    val refreshExpiresAt = Date(System.currentTimeMillis() + refreshLifeTime * DAY_MILLIS)
    val accessToken = JWT.create()
        .withAudience(audience)
        .withIssuer(issuer)
        .withClaim("userId", userId)
        .withClaim("deviceId", deviceId)
        .withClaim("login", login)
        .withClaim("displayName", displayName)
        .withExpiresAt(accessExpiresAt)
        .sign(Algorithm.RSA256(publicKey as RSAPublicKey, privateKey as RSAPrivateKey))
    val refreshToken = UUID.randomUUID().toString()
    return TokenPair(accessToken, refreshToken, refreshExpiresAt.time, login, displayName)
}

@Serializable
data class TokenPair(val accessToken: String, val refreshToken: String, val expiresAt: Long, val login: String, val displayName: String)

@Serializable
data class RefreshToken(val refreshToken: String)

data class UserInfo(val userId: String, val deviceId: String, val login: String, val displayName: String)

private suspend fun getUserInfo(principal: JWTPrincipal?): UserInfo {
    val userId = principal!!.payload.getClaim("userId").asString()
    val deviceId = principal.payload.getClaim("deviceId").asString()
    val login = principal.payload.getClaim("login").asString()
    val displayName = principal.payload.getClaim("displayName").asString()
    return UserInfo(userId, deviceId, login, displayName)
}

private suspend inline fun checkInternalServerError(call: ApplicationCall, block: () -> Unit) {
    try {
        block()
    } catch (e: Exception) {
        call.respond(
            HttpStatusCode.InternalServerError,
            AuthResponse(
                message = "InternalServerError"
            )
        )
    }
}