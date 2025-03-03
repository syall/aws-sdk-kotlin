/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 */

package aws.sdk.kotlin.runtime.auth.credentials

import aws.sdk.kotlin.runtime.config.AwsSdkSetting
import aws.sdk.kotlin.runtime.config.imds.ImdsClient
import aws.smithy.kotlin.runtime.auth.awscredentials.CachedCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.CloseableCredentialsProvider
import aws.smithy.kotlin.runtime.auth.awscredentials.Credentials
import aws.smithy.kotlin.runtime.auth.awscredentials.CredentialsProviderChain
import aws.smithy.kotlin.runtime.http.engine.DefaultHttpEngine
import aws.smithy.kotlin.runtime.http.engine.HttpClientEngine
import aws.smithy.kotlin.runtime.io.Closeable
import aws.smithy.kotlin.runtime.io.closeIfCloseable
import aws.smithy.kotlin.runtime.util.Attributes
import aws.smithy.kotlin.runtime.util.PlatformProvider

/**
 * Default AWS credential provider chain used by most AWS SDKs.
 *
 * Resolution order:
 *
 * 1. Environment variables ([EnvironmentCredentialsProvider])
 * 2. Profile ([ProfileCredentialsProvider])
 * 3. Web Identity Tokens ([StsWebIdentityCredentialsProvider]]
 * 4. ECS (IAM roles for tasks) ([EcsCredentialsProvider])
 * 5. EC2 Instance Metadata (IMDSv2) ([ImdsCredentialsProvider])
 *
 * The chain is decorated with a [CachedCredentialsProvider].
 *
 * Closing the chain will close all child providers that implement [Closeable].
 *
 * @param profileName Override the profile name to use. If not provided it will be resolved internally
 * via environment (see [AwsSdkSetting.AwsProfile]) or defaulted to `default` if not configured.
 * @param platformProvider The platform API provider
 * @param httpClient the [HttpClientEngine] instance to use to make requests. NOTE: This engine's resources and lifetime
 * are NOT managed by the provider. Caller is responsible for closing.
 * @param region the region to make credentials requests to.
 * @return the newly-constructed credentials provider
 */
public class DefaultChainCredentialsProvider constructor(
    public val profileName: String? = null,
    public val platformProvider: PlatformProvider = PlatformProvider.System,
    httpClient: HttpClientEngine? = null,
    public val region: String? = null,
) : CloseableCredentialsProvider {

    private val manageEngine = httpClient == null
    private val engine = httpClient ?: DefaultHttpEngine()

    private val chain = CredentialsProviderChain(
        SystemPropertyCredentialsProvider(platformProvider::getProperty),
        EnvironmentCredentialsProvider(platformProvider::getenv),
        ProfileCredentialsProvider(profileName = profileName, platformProvider = platformProvider, httpClient = engine, region = region),
        // STS web identity provider can be constructed from either the profile OR 100% from the environment
        StsWebIdentityProvider(platformProvider = platformProvider, httpClient = engine),
        EcsCredentialsProvider(platformProvider, engine),
        ImdsCredentialsProvider(
            client = lazy {
                ImdsClient {
                    platformProvider = this@DefaultChainCredentialsProvider.platformProvider
                    engine = this@DefaultChainCredentialsProvider.engine
                }
            },
            platformProvider = platformProvider,
        ),
    )

    private val provider = CachedCredentialsProvider(chain)

    override suspend fun resolve(attributes: Attributes): Credentials = provider.resolve(attributes)

    override fun close() {
        provider.close()
        if (manageEngine) {
            engine.closeIfCloseable()
        }
    }
}

/**
 * Wrapper around [StsWebIdentityCredentialsProvider] that delays any exceptions until [resolve] is invoked.
 * This allows it to be part of the default chain and any failures result in the chain to move onto the next provider.
 */
private class StsWebIdentityProvider(
    val platformProvider: PlatformProvider = PlatformProvider.System,
    val httpClient: HttpClientEngine? = null,
) : CloseableCredentialsProvider {
    override suspend fun resolve(attributes: Attributes): Credentials {
        val wrapped = StsWebIdentityCredentialsProvider.fromEnvironment(platformProvider = platformProvider, httpClient = httpClient)
        return wrapped.resolve(attributes)
    }

    override fun close() { }
}
