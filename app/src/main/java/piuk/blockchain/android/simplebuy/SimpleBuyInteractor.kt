package piuk.blockchain.android.simplebuy

import com.blockchain.swap.nabu.datamanagers.BillingAddress
import com.blockchain.swap.nabu.datamanagers.BuySellOrder
import com.blockchain.swap.nabu.datamanagers.CardToBeActivated
import com.blockchain.swap.nabu.datamanagers.CustodialWalletManager
import com.blockchain.swap.nabu.datamanagers.OrderInput
import com.blockchain.swap.nabu.datamanagers.OrderOutput
import com.blockchain.swap.nabu.datamanagers.OrderState
import com.blockchain.swap.nabu.datamanagers.PaymentMethod
import com.blockchain.swap.nabu.datamanagers.BuySellPairs
import com.blockchain.swap.nabu.datamanagers.EligibilityProvider
import com.blockchain.swap.nabu.datamanagers.custodialwalletimpl.CardStatus
import com.blockchain.swap.nabu.datamanagers.custodialwalletimpl.PaymentMethodType
import com.blockchain.swap.nabu.datamanagers.repositories.WithdrawLocksRepository
import com.blockchain.swap.nabu.models.nabu.KycTierLevel
import com.blockchain.swap.nabu.models.nabu.KycTiers
import com.blockchain.swap.nabu.models.simplebuy.CardPartnerAttributes
import com.blockchain.swap.nabu.models.simplebuy.CustodialWalletOrder
import com.blockchain.swap.nabu.service.TierService
import com.blockchain.ui.trackLoading
import info.blockchain.balance.CryptoCurrency
import info.blockchain.balance.FiatValue
import io.reactivex.Completable
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.rxkotlin.zipWith
import piuk.blockchain.android.cards.CardIntent
import piuk.blockchain.android.coincore.Coincore
import piuk.blockchain.android.util.AppUtil
import java.util.concurrent.TimeUnit

class SimpleBuyInteractor(
    private val tierService: TierService,
    private val custodialWalletManager: CustodialWalletManager,
    private val withdrawLocksRepository: WithdrawLocksRepository,
    private val appUtil: AppUtil,
    private val eligibilityProvider: EligibilityProvider,
    private val coincore: Coincore
) {

    fun fetchBuyLimitsAndSupportedCryptoCurrencies(targetCurrency: String):
            Single<BuySellPairs> =
        custodialWalletManager.getSupportedBuySellCryptoCurrencies(targetCurrency)
            .trackLoading(appUtil.activityIndicator)

    fun fetchSupportedFiatCurrencies(): Single<SimpleBuyIntent.SupportedCurrenciesUpdated> =
        custodialWalletManager.getSupportedFiatCurrencies()
            .map { SimpleBuyIntent.SupportedCurrenciesUpdated(it) }
            .trackLoading(appUtil.activityIndicator)

    fun fetchPredefinedAmounts(targetCurrency: String): Single<SimpleBuyIntent.UpdatedPredefinedAmounts> =
        custodialWalletManager.getPredefinedAmounts(targetCurrency)
            .map {
                SimpleBuyIntent.UpdatedPredefinedAmounts(it.sortedBy { value ->
                    value.valueMinor
                })
            }.trackLoading(appUtil.activityIndicator)

    fun cancelOrder(orderId: String): Completable =
        custodialWalletManager.deleteBuyOrder(orderId)

    fun createOrder(
        cryptoCurrency: CryptoCurrency,
        amount: FiatValue,
        paymentMethodId: String? = null,
        paymentMethod: PaymentMethodType,
        isPending: Boolean
    ): Single<SimpleBuyIntent.OrderCreated> =
        custodialWalletManager.createOrder(
            custodialWalletOrder = CustodialWalletOrder(
                pair = "${cryptoCurrency.networkTicker}-${amount.currencyCode}",
                action = "BUY",
                input = OrderInput(
                    amount.currencyCode, amount.toBigInteger().toString()
                ),
                output = OrderOutput(
                    cryptoCurrency.networkTicker, null
                ),
                paymentMethodId = paymentMethodId,
                paymentType = paymentMethod.name
            ),
            stateAction = if (isPending) "pending" else null
        ).map {
            SimpleBuyIntent.OrderCreated(it)
        }

    fun fetchBankAccount(currency: String): Single<SimpleBuyIntent.BankAccountUpdated> =
        custodialWalletManager.getBankAccountDetails(currency).map {
            SimpleBuyIntent.BankAccountUpdated(it)
        }

    fun fetchWithdrawLockTime(paymentMethod: PaymentMethodType): Single<SimpleBuyIntent.WithdrawLocksTimeUpdated> =
        withdrawLocksRepository.getWithdrawLockTypeForPaymentMethod(paymentMethod)
            .map {
                SimpleBuyIntent.WithdrawLocksTimeUpdated(it)
            }.onErrorReturn {
                SimpleBuyIntent.WithdrawLocksTimeUpdated()
            }

    fun fetchQuote(cryptoCurrency: CryptoCurrency?, amount: FiatValue?): Single<SimpleBuyIntent.QuoteUpdated> =
        custodialWalletManager.getQuote(
            cryptoCurrency = cryptoCurrency ?: throw IllegalStateException("Missing Cryptocurrency "),
            fiatCurrency = amount?.currencyCode ?: throw IllegalStateException("Missing FiatCurrency "),
            action = "BUY",
            currency = amount.currencyCode,
            amount = amount.toBigInteger().toString()).map {
            SimpleBuyIntent.QuoteUpdated(it)
        }

    fun pollForKycState(fiatCurrency: String): Single<SimpleBuyIntent.KycStateUpdated> =
        tierService.tiers()
            .flatMap {
                when {
                    it.isApprovedFor(KycTierLevel.GOLD) ->
                        eligibilityProvider.isEligibleForSimpleBuy(forceRefresh = true).map { eligible ->
                            if (eligible) {
                                SimpleBuyIntent.KycStateUpdated(KycState.VERIFIED_AND_ELIGIBLE)
                            } else {
                                SimpleBuyIntent.KycStateUpdated(KycState.VERIFIED_BUT_NOT_ELIGIBLE)
                            }
                        }
                    it.isRejectedForAny() -> Single.just(SimpleBuyIntent.KycStateUpdated(KycState.FAILED))
                    it.isInReviewForAny() -> Single.just(SimpleBuyIntent.KycStateUpdated(KycState.IN_REVIEW))
                    else -> Single.just(SimpleBuyIntent.KycStateUpdated(KycState.PENDING))
                }
            }.onErrorReturn {
                SimpleBuyIntent.KycStateUpdated(KycState.PENDING)
            }
            .repeatWhen { it.delay(5, TimeUnit.SECONDS).zipWith(Flowable.range(0, 6)) }
            .takeUntil { it.kycState != KycState.PENDING }
            .last(SimpleBuyIntent.KycStateUpdated(KycState.PENDING))
            .map {
                if (it.kycState == KycState.PENDING) {
                    SimpleBuyIntent.KycStateUpdated(KycState.UNDECIDED)
                } else {
                    it
                }
            }

    fun checkTierLevel(fiatCurrency: String): Single<SimpleBuyIntent.KycStateUpdated> {

        return tierService.tiers().flatMap {
            when {
                it.isApprovedFor(KycTierLevel.GOLD) -> eligibilityProvider.isEligibleForSimpleBuy(forceRefresh = true)
                    .map { eligible ->
                        if (eligible) {
                            SimpleBuyIntent.KycStateUpdated(KycState.VERIFIED_AND_ELIGIBLE)
                        } else {
                            SimpleBuyIntent.KycStateUpdated(KycState.VERIFIED_BUT_NOT_ELIGIBLE)
                        }
                    }
                it.isRejectedFor(KycTierLevel.GOLD) -> Single.just(SimpleBuyIntent.KycStateUpdated(KycState.FAILED))
                it.isPendingFor(KycTierLevel.GOLD) -> Single.just(SimpleBuyIntent.KycStateUpdated(KycState.IN_REVIEW))
                else -> Single.just(SimpleBuyIntent.KycStateUpdated(KycState.PENDING))
            }
        }.onErrorReturn { SimpleBuyIntent.KycStateUpdated(KycState.PENDING) }
    }

    private fun KycTiers.isRejectedForAny(): Boolean =
        isRejectedFor(KycTierLevel.SILVER) ||
                isRejectedFor(KycTierLevel.GOLD)

    private fun KycTiers.isInReviewForAny(): Boolean =
        isUnderReviewFor(KycTierLevel.SILVER) ||
                isUnderReviewFor(KycTierLevel.GOLD)

    fun exchangeRate(cryptoCurrency: CryptoCurrency): Single<SimpleBuyIntent.ExchangeRateUpdated> =
        coincore[cryptoCurrency].exchangeRate().map {
            SimpleBuyIntent.ExchangeRateUpdated(it.price() as FiatValue)
        }

    fun fetchPaymentMethods(fiatCurrency: String, preselectedId: String?):
            Single<SimpleBuyIntent.PaymentMethodsUpdated> =
        tierService.tiers().flatMap { tier ->
            custodialWalletManager.fetchSuggestedPaymentMethod(fiatCurrency,
                tier.isApprovedFor(KycTierLevel.GOLD)
            ).map { paymentMethods ->
                SimpleBuyIntent.PaymentMethodsUpdated(
                    availablePaymentMethods = paymentMethods,
                    canAddCard = tier.isApprovedFor(KycTierLevel.GOLD),
                    canLinkFunds = tier.isApprovedFor(KycTierLevel.GOLD),
                    preselectedId = if (tier.isApprovedFor(
                            KycTierLevel.GOLD) || preselectedId != null
                    ) {
                        preselectedId
                    } else {
                        PaymentMethod.UNDEFINED_PAYMENT_ID
                    }
                )
            }
        }

    // attributes are null in case of bank
    fun confirmOrder(orderId: String, attributes: CardPartnerAttributes?): Single<BuySellOrder> =
        custodialWalletManager.confirmOrder(orderId, attributes)

    fun pollForOrderStatus(orderId: String): Single<BuySellOrder> =
        custodialWalletManager.getBuyOrder(orderId)
            .repeatWhen { it.delay(5, TimeUnit.SECONDS).zipWith(Flowable.range(0, 20)) }
            .takeUntil {
                it.state == OrderState.FINISHED ||
                        it.state == OrderState.FAILED ||
                        it.state == OrderState.CANCELED
            }.lastOrError()

    fun pollForCardStatus(cardId: String): Single<CardIntent.CardUpdated> =
        custodialWalletManager.getCardDetails(cardId)
            .repeatWhen { it.delay(5, TimeUnit.SECONDS).zipWith(Flowable.range(0, 24)) }
            .takeUntil {
                it.status == CardStatus.BLOCKED ||
                        it.status == CardStatus.EXPIRED ||
                        it.status == CardStatus.ACTIVE
            }
            .map {
                CardIntent.CardUpdated(it)
            }
            .lastOrError()

    fun fetchOrder(orderId: String) = custodialWalletManager.getBuyOrder(orderId)

    fun addNewCard(fiatCurrency: String, billingAddress: BillingAddress): Single<CardToBeActivated> =
        custodialWalletManager.addNewCard(fiatCurrency, billingAddress)
}