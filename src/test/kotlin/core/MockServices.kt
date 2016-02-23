/*
 * Copyright 2015 Distributed Ledger Group LLC.  Distributed as Licensed Company IP to DLG Group Members
 * pursuant to the August 7, 2015 Advisory Services Agreement and subject to the Company IP License terms
 * set forth therein.
 *
 * All other rights reserved.
 */

package core

import core.crypto.DigitalSignature
import core.crypto.SecureHash
import core.crypto.generateKeyPair
import core.crypto.signWithECDSA
import core.messaging.MessagingService
import core.messaging.MockNetworkMap
import core.messaging.NetworkMap
import core.node.DataVendingService
import core.node.TimestampingError
import core.serialization.SerializedBytes
import core.serialization.deserialize
import core.testutils.RecordingMap
import core.testutils.TEST_KEYS_TO_CORP_MAP
import core.testutils.TEST_PROGRAM_MAP
import core.testutils.TEST_TX_TIME
import org.slf4j.LoggerFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.time.Clock
import java.time.Duration
import java.time.ZoneId
import java.util.*
import javax.annotation.concurrent.ThreadSafe

/**
 * A test/mock timestamping service that doesn't use any signatures or security. It timestamps with
 * the provided clock which defaults to [TEST_TX_TIME], an arbitrary point on the timeline.
 */
class DummyTimestamper(var clock: Clock = Clock.fixed(TEST_TX_TIME, ZoneId.systemDefault()),
                       val tolerance: Duration = 30.seconds) : TimestamperService {
    override val identity = DummyTimestampingAuthority.identity

    override fun timestamp(wtxBytes: SerializedBytes<WireTransaction>): DigitalSignature.LegallyIdentifiable {
        val wtx = wtxBytes.deserialize()
        val timestamp = wtx.commands.mapNotNull { it.data as? TimestampCommand }.single()
        if (timestamp.before!! until clock.instant() > tolerance)
            throw TimestampingError.NotOnTimeException()
        return DummyTimestampingAuthority.key.signWithECDSA(wtxBytes.bits, identity)
    }
}

val DUMMY_TIMESTAMPER = DummyTimestamper()

object MockIdentityService : IdentityService {
    override fun partyFromKey(key: PublicKey): Party? = TEST_KEYS_TO_CORP_MAP[key]
}

class MockKeyManagementService(
        override val keys: Map<PublicKey, PrivateKey>,
        val nextKeys: MutableList<KeyPair> = arrayListOf(generateKeyPair())
) : KeyManagementService {
    override fun freshKey() = nextKeys.removeAt(nextKeys.lastIndex)
}

class MockWalletService(val states: List<StateAndRef<OwnableState>>) : WalletService {
    override val currentWallet = Wallet(states)
}

@ThreadSafe
class MockStorageService(val recordingAs: Map<String, String>? = null) : StorageService {
    override val myLegalIdentityKey: KeyPair = generateKeyPair()
    override val myLegalIdentity: Party = Party("Unit test party", myLegalIdentityKey.public)

    private val tables = HashMap<String, MutableMap<Any, Any>>()

    override val validatedTransactions: MutableMap<SecureHash, SignedTransaction>
        get() = getMap("validated-transactions")

    override val contractPrograms = MockContractFactory

    @Suppress("UNCHECKED_CAST")
    override fun <K, V> getMap(tableName: String): MutableMap<K, V> {
        synchronized(tables) {
            return tables.getOrPut(tableName) {
                val map = Collections.synchronizedMap(HashMap<Any, Any>())
                if (recordingAs != null && recordingAs[tableName] != null)
                    RecordingMap(map, LoggerFactory.getLogger("recordingmap.${recordingAs[tableName]}"))
                else
                    map
            } as MutableMap<K, V>
        }
    }
}

object MockContractFactory : ContractFactory {
    override operator fun <T : Contract> get(hash: SecureHash): T {
        val clazz = TEST_PROGRAM_MAP[hash] ?: throw UnknownContractException()
        @Suppress("UNCHECKED_CAST")
        return clazz.newInstance() as T
    }
}

class MockServices(
        val wallet: WalletService? = null,
        val keyManagement: KeyManagementService? = null,
        val net: MessagingService? = null,
        val identity: IdentityService? = MockIdentityService,
        val storage: StorageService? = MockStorageService(),
        val networkMap: NetworkMap? = MockNetworkMap()
) : ServiceHub {
    override val walletService: WalletService
        get() = wallet ?: throw UnsupportedOperationException()
    override val keyManagementService: KeyManagementService
        get() = keyManagement ?: throw UnsupportedOperationException()
    override val identityService: IdentityService
        get() = identity ?: throw UnsupportedOperationException()
    override val networkService: MessagingService
        get() = net ?: throw UnsupportedOperationException()
    override val networkMapService: NetworkMap
        get() = networkMap ?: throw UnsupportedOperationException()
    override val storageService: StorageService
        get() = storage ?: throw UnsupportedOperationException()

    init {
        if (net != null && storage != null) {
            // Creating this class is sufficient, we don't have to store it anywhere, because it registers a listener
            // on the networking service, so that will keep it from being collected.
            DataVendingService(net, storage)
        }
    }
}