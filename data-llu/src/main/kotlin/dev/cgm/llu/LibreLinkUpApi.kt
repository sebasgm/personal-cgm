package dev.cgm.llu

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

@kotlinx.serialization.Serializable
internal data class LoginRequest(val email: String, val password: String)

internal interface LibreLinkUpApi {

    @POST("llu/auth/login")
    suspend fun login(@Body body: LoginRequest): Response<Envelope<LoginData>>

    @GET("llu/connections")
    suspend fun connections(): Response<Envelope<List<Connection>>>

    @GET("llu/connections/{patientId}/graph")
    suspend fun graph(@Path("patientId") patientId: String): Response<Envelope<GraphData>>
}
