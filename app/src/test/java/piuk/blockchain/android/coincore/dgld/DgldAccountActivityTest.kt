package piuk.blockchain.android.coincore.dgld

import com.blockchain.android.testutils.rxInit
import com.blockchain.preferences.CurrencyPrefs
import com.blockchain.preferences.WalletStatus
import com.blockchain.swap.nabu.datamanagers.CustodialWalletManager
import com.blockchain.swap.nabu.datamanagers.SwapDirection
import com.blockchain.swap.nabu.datamanagers.SwapOrderState
import com.blockchain.swap.nabu.datamanagers.repositories.swap.SwapTransactionItem
import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.verify
import com.nhaarman.mockito_kotlin.whenever
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.CryptoValue
import info.blockchain.balance.FiatValue
import info.blockchain.wallet.ethereum.data.EthLatestBlockNumber
import info.blockchain.wallet.ethereum.data.EthTransaction
import info.blockchain.wallet.multiaddress.TransactionSummary
import io.reactivex.Observable
import io.reactivex.Single
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import piuk.blockchain.android.coincore.erc20.Erc20ActivitySummaryItem
import piuk.blockchain.android.coincore.erc20.dgld.DgldCryptoWalletAccount
import piuk.blockchain.androidcore.data.erc20.Erc20Account
import piuk.blockchain.androidcore.data.erc20.Erc20Transfer
import piuk.blockchain.androidcore.data.ethereum.EthDataManager
import piuk.blockchain.androidcore.data.exchangerate.ExchangeRateDataManager
import piuk.blockchain.androidcore.data.payload.PayloadDataManager

class DgldAccountActivityTest {

    private val payloadManager: PayloadDataManager = mock()
    private val ethDataManager: EthDataManager = mock()
    private val exchangeRates: ExchangeRateDataManager = mock()
    private val currencyPrefs: CurrencyPrefs = mock()
    private val walletPreferences: WalletStatus = mock()
    private val custodialWalletManager: CustodialWalletManager = mock()

    private val dgldAccount: Erc20Account = mock()

    private val subject =
        DgldCryptoWalletAccount(
            payloadManager = payloadManager,
            label = "Text Dgld Account",
            address = "Test Dgld Address",
            fees = mock(),
            erc20Account = dgldAccount,
            exchangeRates = exchangeRates,
            walletPreferences = walletPreferences,
            custodialWalletManager = custodialWalletManager
        )

    @get:Rule
    val rxSchedulers = rxInit {
        mainTrampoline()
        ioTrampoline()
        computationTrampoline()
    }

    @Before
    fun setup() {
        whenever(currencyPrefs.selectedFiatCurrency).thenReturn("USD")
        whenever(dgldAccount.ethDataManager).thenReturn(ethDataManager)
    }

    @Test
    fun getErc20TransactionsList() {
        val erc20Transfer = Erc20Transfer(
            logIndex = "132",
            from = "0x4058a004dd718babab47e14dd0d744742e5b9903",
            to = "0x2ca28ffadd20474ffe2705580279a1e67cd10a29",
            value = 10000.toBigInteger(),
            transactionHash = "0xfd7d583fa54bf55f6cfbfec97c0c55cc6af8c121b71addb7d06a9e1e305ae8ff",
            blockNumber = 7721219.toBigInteger(),
            timestamp = 1557334297
        )

        val swapSummary = SwapTransactionItem(
            "123",
            1L,
            SwapDirection.ON_CHAIN,
            "sendingAddress",
            "receivingAddress",
            SwapOrderState.FINISHED,
            CryptoValue.ZeroDgld,
            CryptoValue.ZeroBtc,
            CryptoValue.ZeroBtc,
            CryptoCurrency.DGLD,
            CryptoCurrency.BTC,
            FiatValue.zero("USD"),
            "USD"
        )

        val summaryList = listOf(swapSummary)

        whenever(dgldAccount.getTransactions()).thenReturn(Observable.just(listOf(erc20Transfer)))

        whenever(ethDataManager
            .getTransaction("0xfd7d583fa54bf55f6cfbfec97c0c55cc6af8c121b71addb7d06a9e1e305ae8ff"))
            .thenReturn(Observable.just(
                EthTransaction(
                    gasPrice = 100.toBigInteger(),
                    gasUsed = 2.toBigInteger()
            )
        ))

        whenever(dgldAccount.fetchErc20Address()).thenReturn(Observable.just(mock()))

        whenever(dgldAccount.getAccountHash())
            .thenReturn(Single.just("0x4058a004dd718babab47e14dd0d744742e5b9903"))

        whenever(ethDataManager.getLatestBlockNumber())
            .thenReturn(Single.just(
                EthLatestBlockNumber().apply {
                    number = erc20Transfer.blockNumber.plus(3.toBigInteger())
                }
            )
        )

        whenever(custodialWalletManager.getSwapActivityForAsset(any(), any()))
            .thenReturn(Single.just(summaryList))

        subject.activity
            .test()
            .assertValueCount(1)
            .assertComplete()
            .assertNoErrors()
            .assertValue {
                it.size == 1 && it[0].run {
                    this is Erc20ActivitySummaryItem &&
                    cryptoCurrency == CryptoCurrency.DGLD &&
                    !doubleSpend &&
                    !isFeeTransaction &&
                    confirmations == 3 &&
                    timeStampMs == 1557334297000L &&
                    transactionType == TransactionSummary.TransactionType.SENT &&
                    txId == "0xfd7d583fa54bf55f6cfbfec97c0c55cc6af8c121b71addb7d06a9e1e305ae8ff" &&
                    confirmations == 3 &&
                    value == CryptoValue.fromMinor(CryptoCurrency.DGLD, 10000.toBigInteger()) &&
                    inputsMap["0x4058a004dd718babab47e14dd0d744742e5b9903"] ==
                        CryptoValue.fromMinor(CryptoCurrency.DGLD, 10000.toBigInteger()) &&
                    outputsMap["0x2ca28ffadd20474ffe2705580279a1e67cd10a29"] ==
                        CryptoValue.fromMinor(CryptoCurrency.DGLD, 10000.toBigInteger())
                }
            }

        verify(dgldAccount).getTransactions()
    }
}
