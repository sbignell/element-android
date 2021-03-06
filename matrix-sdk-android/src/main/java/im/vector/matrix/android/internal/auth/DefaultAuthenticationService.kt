/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.auth

import android.net.Uri
import dagger.Lazy
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.auth.AuthenticationService
import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.api.auth.data.HomeServerConnectionConfig
import im.vector.matrix.android.api.auth.data.LoginFlowResult
import im.vector.matrix.android.api.auth.login.LoginWizard
import im.vector.matrix.android.api.auth.registration.RegistrationWizard
import im.vector.matrix.android.api.auth.wellknown.WellknownResult
import im.vector.matrix.android.api.failure.Failure
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.api.util.NoOpCancellable
import im.vector.matrix.android.internal.SessionManager
import im.vector.matrix.android.internal.auth.data.LoginFlowResponse
import im.vector.matrix.android.internal.auth.data.RiotConfig
import im.vector.matrix.android.internal.auth.db.PendingSessionData
import im.vector.matrix.android.internal.auth.login.DefaultLoginWizard
import im.vector.matrix.android.internal.auth.login.DirectLoginTask
import im.vector.matrix.android.internal.auth.registration.DefaultRegistrationWizard
import im.vector.matrix.android.internal.auth.version.Versions
import im.vector.matrix.android.internal.auth.version.isLoginAndRegistrationSupportedBySdk
import im.vector.matrix.android.internal.auth.version.isSupportedBySdk
import im.vector.matrix.android.internal.di.Unauthenticated
import im.vector.matrix.android.internal.network.RetrofitFactory
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.network.httpclient.addSocketFactory
import im.vector.matrix.android.internal.network.ssl.UnrecognizedCertificateException
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.configureWith
import im.vector.matrix.android.internal.task.launchToCallback
import im.vector.matrix.android.internal.util.MatrixCoroutineDispatchers
import im.vector.matrix.android.internal.util.exhaustive
import im.vector.matrix.android.internal.util.toCancelable
import im.vector.matrix.android.internal.wellknown.GetWellknownTask
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

internal class DefaultAuthenticationService @Inject constructor(
        @Unauthenticated
        private val okHttpClient: Lazy<OkHttpClient>,
        private val retrofitFactory: RetrofitFactory,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val sessionParamsStore: SessionParamsStore,
        private val sessionManager: SessionManager,
        private val sessionCreator: SessionCreator,
        private val pendingSessionStore: PendingSessionStore,
        private val getWellknownTask: GetWellknownTask,
        private val directLoginTask: DirectLoginTask,
        private val taskExecutor: TaskExecutor
) : AuthenticationService {

    private var pendingSessionData: PendingSessionData? = pendingSessionStore.getPendingSessionData()

    private var currentLoginWizard: LoginWizard? = null
    private var currentRegistrationWizard: RegistrationWizard? = null

    override fun hasAuthenticatedSessions(): Boolean {
        return sessionParamsStore.getLast() != null
    }

    override fun getLastAuthenticatedSession(): Session? {
        val sessionParams = sessionParamsStore.getLast()
        return sessionParams?.let {
            sessionManager.getOrCreateSession(it)
        }
    }

    override fun getLoginFlowOfSession(sessionId: String, callback: MatrixCallback<LoginFlowResult>): Cancelable {
        val homeServerConnectionConfig = sessionParamsStore.get(sessionId)?.homeServerConnectionConfig

        return if (homeServerConnectionConfig == null) {
            callback.onFailure(IllegalStateException("Session not found"))
            NoOpCancellable
        } else {
            getLoginFlow(homeServerConnectionConfig, callback)
        }
    }

    override fun getLoginFlow(homeServerConnectionConfig: HomeServerConnectionConfig, callback: MatrixCallback<LoginFlowResult>): Cancelable {
        pendingSessionData = null

        return taskExecutor.executorScope.launch(coroutineDispatchers.main) {
            pendingSessionStore.delete()

            val result = runCatching {
                getLoginFlowInternal(homeServerConnectionConfig)
            }
            result.fold(
                    {
                        if (it is LoginFlowResult.Success) {
                            // The homeserver exists and up to date, keep the config
                            // Homeserver url may have been changed, if it was a Riot url
                            val alteredHomeServerConnectionConfig = homeServerConnectionConfig.copy(
                                    homeServerUri = Uri.parse(it.homeServerUrl)
                            )

                            pendingSessionData = PendingSessionData(alteredHomeServerConnectionConfig)
                                    .also { data -> pendingSessionStore.savePendingSessionData(data) }
                        }
                        callback.onSuccess(it)
                    },
                    {
                        if (it is UnrecognizedCertificateException) {
                            callback.onFailure(Failure.UnrecognizedCertificateFailure(homeServerConnectionConfig.homeServerUri.toString(), it.fingerprint))
                        } else {
                            callback.onFailure(it)
                        }
                    }
            )
        }
                .toCancelable()
    }

    private suspend fun getLoginFlowInternal(homeServerConnectionConfig: HomeServerConnectionConfig): LoginFlowResult {
        return withContext(coroutineDispatchers.io) {
            val authAPI = buildAuthAPI(homeServerConnectionConfig)

            // First check the homeserver version
            runCatching {
                executeRequest<Versions>(null) {
                    apiCall = authAPI.versions()
                }
            }
                    .map { versions ->
                        // Ok, it seems that the homeserver url is valid
                        getLoginFlowResult(authAPI, versions, homeServerConnectionConfig.homeServerUri.toString())
                    }
                    .fold(
                            {
                                it
                            },
                            {
                                if (it is Failure.OtherServerError
                                        && it.httpCode == HttpsURLConnection.HTTP_NOT_FOUND /* 404 */) {
                                    // It's maybe a Riot url?
                                    getRiotLoginFlowInternal(homeServerConnectionConfig)
                                } else {
                                    throw it
                                }
                            }
                    )
        }
    }

    private suspend fun getRiotLoginFlowInternal(homeServerConnectionConfig: HomeServerConnectionConfig): LoginFlowResult {
        val authAPI = buildAuthAPI(homeServerConnectionConfig)

        // Ok, try to get the config.json file of a RiotWeb client
        return runCatching {
            executeRequest<RiotConfig>(null) {
                apiCall = authAPI.getRiotConfig()
            }
        }
                .map { riotConfig ->
                    if (riotConfig.defaultHomeServerUrl?.isNotBlank() == true) {
                        // Ok, good sign, we got a default hs url
                        val newHomeServerConnectionConfig = homeServerConnectionConfig.copy(
                                homeServerUri = Uri.parse(riotConfig.defaultHomeServerUrl)
                        )

                        val newAuthAPI = buildAuthAPI(newHomeServerConnectionConfig)

                        val versions = executeRequest<Versions>(null) {
                            apiCall = newAuthAPI.versions()
                        }

                        getLoginFlowResult(newAuthAPI, versions, riotConfig.defaultHomeServerUrl)
                    } else {
                        // Config exists, but there is no default homeserver url (ex: https://riot.im/app)
                        throw Failure.OtherServerError("", HttpsURLConnection.HTTP_NOT_FOUND /* 404 */)
                    }
                }
                .fold(
                        {
                            it
                        },
                        {
                            if (it is Failure.OtherServerError
                                    && it.httpCode == HttpsURLConnection.HTTP_NOT_FOUND /* 404 */) {
                                // Try with wellknown
                                getWellknownLoginFlowInternal(homeServerConnectionConfig)
                            } else {
                                throw it
                            }
                        }
                )
    }

    private suspend fun getWellknownLoginFlowInternal(homeServerConnectionConfig: HomeServerConnectionConfig): LoginFlowResult {
        val domain = homeServerConnectionConfig.homeServerUri.host
                ?: throw Failure.OtherServerError("", HttpsURLConnection.HTTP_NOT_FOUND /* 404 */)

        // Create a fake userId, for the getWellknown task
        val fakeUserId = "@alice:$domain"
        val wellknownResult = getWellknownTask.execute(GetWellknownTask.Params(fakeUserId, homeServerConnectionConfig))

        return when (wellknownResult) {
            is WellknownResult.Prompt -> {
                val newHomeServerConnectionConfig = homeServerConnectionConfig.copy(
                        homeServerUri = Uri.parse(wellknownResult.homeServerUrl),
                        identityServerUri = wellknownResult.identityServerUrl?.let { Uri.parse(it) }
                )

                val newAuthAPI = buildAuthAPI(newHomeServerConnectionConfig)

                val versions = executeRequest<Versions>(null) {
                    apiCall = newAuthAPI.versions()
                }

                getLoginFlowResult(newAuthAPI, versions, wellknownResult.homeServerUrl)
            }
            else                      -> throw Failure.OtherServerError("", HttpsURLConnection.HTTP_NOT_FOUND /* 404 */)
        }.exhaustive
    }

    private suspend fun getLoginFlowResult(authAPI: AuthAPI, versions: Versions, homeServerUrl: String): LoginFlowResult {
        return if (versions.isSupportedBySdk()) {
            // Get the login flow
            val loginFlowResponse = executeRequest<LoginFlowResponse>(null) {
                apiCall = authAPI.getLoginFlows()
            }
            LoginFlowResult.Success(loginFlowResponse.flows.orEmpty().mapNotNull { it.type }, versions.isLoginAndRegistrationSupportedBySdk(), homeServerUrl)
        } else {
            // Not supported
            LoginFlowResult.OutdatedHomeserver
        }
    }

    override fun getRegistrationWizard(): RegistrationWizard {
        return currentRegistrationWizard
                ?: let {
                    pendingSessionData?.homeServerConnectionConfig?.let {
                        DefaultRegistrationWizard(
                                buildClient(it),
                                retrofitFactory,
                                coroutineDispatchers,
                                sessionCreator,
                                pendingSessionStore,
                                taskExecutor.executorScope
                        ).also {
                            currentRegistrationWizard = it
                        }
                    } ?: error("Please call getLoginFlow() with success first")
                }
    }

    override val isRegistrationStarted: Boolean
        get() = currentRegistrationWizard?.isRegistrationStarted == true

    override fun getLoginWizard(): LoginWizard {
        return currentLoginWizard
                ?: let {
                    pendingSessionData?.homeServerConnectionConfig?.let {
                        DefaultLoginWizard(
                                buildClient(it),
                                retrofitFactory,
                                coroutineDispatchers,
                                sessionCreator,
                                pendingSessionStore,
                                taskExecutor.executorScope
                        ).also {
                            currentLoginWizard = it
                        }
                    } ?: error("Please call getLoginFlow() with success first")
                }
    }

    override fun cancelPendingLoginOrRegistration() {
        currentLoginWizard = null
        currentRegistrationWizard = null

        // Keep only the home sever config
        // Update the local pendingSessionData synchronously
        pendingSessionData = pendingSessionData?.homeServerConnectionConfig
                ?.let { PendingSessionData(it) }
                .also {
                    taskExecutor.executorScope.launch(coroutineDispatchers.main) {
                        if (it == null) {
                            // Should not happen
                            pendingSessionStore.delete()
                        } else {
                            pendingSessionStore.savePendingSessionData(it)
                        }
                    }
                }
    }

    override fun reset() {
        currentLoginWizard = null
        currentRegistrationWizard = null

        pendingSessionData = null

        taskExecutor.executorScope.launch(coroutineDispatchers.main) {
            pendingSessionStore.delete()
        }
    }

    override fun createSessionFromSso(homeServerConnectionConfig: HomeServerConnectionConfig,
                                      credentials: Credentials,
                                      callback: MatrixCallback<Session>): Cancelable {
        return taskExecutor.executorScope.launchToCallback(coroutineDispatchers.main, callback) {
            createSessionFromSso(credentials, homeServerConnectionConfig)
        }
    }

    override fun getWellKnownData(matrixId: String,
                                  homeServerConnectionConfig: HomeServerConnectionConfig?,
                                  callback: MatrixCallback<WellknownResult>): Cancelable {
        return getWellknownTask
                .configureWith(GetWellknownTask.Params(matrixId, homeServerConnectionConfig)) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun directAuthentication(homeServerConnectionConfig: HomeServerConnectionConfig,
                                      matrixId: String,
                                      password: String,
                                      initialDeviceName: String,
                                      callback: MatrixCallback<Session>): Cancelable {
        return directLoginTask
                .configureWith(DirectLoginTask.Params(homeServerConnectionConfig, matrixId, password, initialDeviceName)) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    private suspend fun createSessionFromSso(credentials: Credentials,
                                             homeServerConnectionConfig: HomeServerConnectionConfig): Session = withContext(coroutineDispatchers.computation) {
        sessionCreator.createSession(credentials, homeServerConnectionConfig)
    }

    private fun buildAuthAPI(homeServerConnectionConfig: HomeServerConnectionConfig): AuthAPI {
        val retrofit = retrofitFactory.create(buildClient(homeServerConnectionConfig), homeServerConnectionConfig.homeServerUri.toString())
        return retrofit.create(AuthAPI::class.java)
    }

    private fun buildClient(homeServerConnectionConfig: HomeServerConnectionConfig): OkHttpClient {
        return okHttpClient.get()
                .newBuilder()
                .addSocketFactory(homeServerConnectionConfig)
                .build()
    }
}
