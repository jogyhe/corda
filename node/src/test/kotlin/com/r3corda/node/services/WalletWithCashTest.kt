package com.r3corda.node.services

import com.r3corda.contracts.asset.Cash
import com.r3corda.contracts.asset.cashBalances
import com.r3corda.contracts.testing.fillWithSomeTestCash
import com.r3corda.core.contracts.*
import com.r3corda.core.crypto.SecureHash
import com.r3corda.core.node.ServiceHub
import com.r3corda.core.node.services.testing.MockKeyManagementService
import com.r3corda.core.node.services.testing.MockStorageService
import com.r3corda.core.serialization.OpaqueBytes
import com.r3corda.core.testing.*
import com.r3corda.core.utilities.BriefLogFormatter
import com.r3corda.node.services.wallet.NodeWalletService
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNull

// TODO: Move this to the cash contract tests once mock services are further split up.

class WalletWithCashTest {
    val kms = MockKeyManagementService(ALICE_KEY)

    @Before
    fun setUp() {
        BriefLogFormatter.loggingOn(NodeWalletService::class)
    }

    @After
    fun tearDown() {
        BriefLogFormatter.loggingOff(NodeWalletService::class)
    }

    fun make(): Pair<NodeWalletService, ServiceHub> {
        val services = MockServices(keyManagement = kms)
        return Pair(services.walletService as NodeWalletService, services)
    }

    @Test
    fun splits() {
        val (wallet, services) = make()
        val ref = OpaqueBytes(ByteArray(1, {0}))

        kms.nextKeys += Array(3) { ALICE_KEY }
        // Fix the PRNG so that we get the same splits every time.
        services.fillWithSomeTestCash(100.DOLLARS, DUMMY_NOTARY, 3, 3, Random(0L), ref)

        val w = wallet.currentWallet
        assertEquals(3, w.states.size)

        val state = w.states[0].state.data as Cash.State
        val myIdentity = services.storageService.myLegalIdentity
        val myPartyRef = myIdentity.ref(ref)
        assertEquals(29.01.DOLLARS `issued by` myPartyRef, state.amount)
        assertEquals(ALICE_PUBKEY, state.owner)

        assertEquals(35.38.DOLLARS `issued by` myPartyRef, (w.states[2].state.data as Cash.State).amount)
        assertEquals(35.61.DOLLARS `issued by` myPartyRef, (w.states[1].state.data as Cash.State).amount)
    }

    @Test
    fun basics() {
        val (wallet, services) = make()

        // A tx that sends us money.
        val freshKey = services.keyManagementService.freshKey()
        val usefulTX = TransactionType.General.Builder().apply {
            Cash().generateIssue(this, 100.DOLLARS `issued by` MEGA_CORP.ref(1), freshKey.public, DUMMY_NOTARY)
            signWith(MEGA_CORP_KEY)
        }.toSignedTransaction()
        val myOutput = usefulTX.verifyToLedgerTransaction(MOCK_IDENTITY_SERVICE, MockStorageService().attachments).outRef<Cash.State>(0)

        // A tx that spends our money.
        val spendTX = TransactionType.General.Builder().apply {
            Cash().generateSpend(this, 80.DOLLARS `issued by` MEGA_CORP.ref(1), BOB_PUBKEY, listOf(myOutput))
            signWith(freshKey)
            signWith(DUMMY_NOTARY_KEY)
        }.toSignedTransaction()

        // A tx that doesn't send us anything.
        val irrelevantTX = TransactionType.General.Builder().apply {
            Cash().generateIssue(this, 100.DOLLARS `issued by` MEGA_CORP.ref(1), BOB_KEY.public, DUMMY_NOTARY)
            signWith(MEGA_CORP_KEY)
            signWith(DUMMY_NOTARY_KEY)
        }.toSignedTransaction()

        assertNull(wallet.currentWallet.cashBalances[USD])
        wallet.notify(usefulTX.tx)
        assertEquals(100.DOLLARS, wallet.currentWallet.cashBalances[USD])
        wallet.notify(irrelevantTX.tx)
        assertEquals(100.DOLLARS, wallet.currentWallet.cashBalances[USD])
        wallet.notify(spendTX.tx)
        assertEquals(20.DOLLARS, wallet.currentWallet.cashBalances[USD])

        // TODO: Flesh out these tests as needed.
    }


    @Test
    fun branchingLinearStatesFails() {
        val (wallet, services) = make()

        val freshKey = services.keyManagementService.freshKey()

        val thread = SecureHash.sha256("thread")

        // Issue a linear state
        val dummyIssue = TransactionType.General.Builder(notary = DUMMY_NOTARY).apply {
            addOutputState(DummyLinearState(thread = thread, participants = listOf(freshKey.public)))
            signWith(freshKey)
        }.toSignedTransaction()

        wallet.notify(dummyIssue.tx)
        assertEquals(1, wallet.currentWallet.states.size)

        // Issue another linear state of the same thread (nonce different)
        val dummyIssue2 = TransactionType.General.Builder(notary = DUMMY_NOTARY).apply {
            addOutputState(DummyLinearState(thread = thread, participants = listOf(freshKey.public)))
            signWith(freshKey)
        }.toSignedTransaction()

        assertThatThrownBy {
            wallet.notify(dummyIssue2.tx)
        }
        assertEquals(1, wallet.currentWallet.states.size)
    }

    @Test
    fun sequencingLinearStatesWorks() {
        val (wallet, services) = make()

        val freshKey = services.keyManagementService.freshKey()

        val thread = SecureHash.sha256("thread")

        // Issue a linear state
        val dummyIssue = TransactionType.General.Builder(notary = DUMMY_NOTARY).apply {
            addOutputState(DummyLinearState(thread = thread, participants = listOf(freshKey.public)))
            signWith(freshKey)
        }.toSignedTransaction()

        wallet.notify(dummyIssue.tx)
        assertEquals(1, wallet.currentWallet.states.size)

        // Move the same state
        val dummyMove = TransactionType.General.Builder(notary = DUMMY_NOTARY).apply {
            addOutputState(DummyLinearState(thread = thread, participants = listOf(freshKey.public)))
            addInputState(dummyIssue.tx.outRef<LinearState>(0))
            signWith(DUMMY_NOTARY_KEY)
        }.toSignedTransaction()

        wallet.notify(dummyMove.tx)
        assertEquals(1, wallet.currentWallet.states.size)
    }
}