package com.cvdoor.app.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Google Play Billing 6.x 封裝 */
class BillingManager(context: Context) : PurchasesUpdatedListener {

    companion object {
        // 在 Google Play Console 建立此 product ID（一次性購買）
        const val PRODUCT_ID = "ats_optimization_one_time"
        // 測試用：免費 SKU（Debug build 下 Play 提供的測試 token）
        const val PRICE_DISPLAY = "HK$4.9"
        const val PRICE_ORIGINAL = "HK$9.9"

        @Volatile private var INSTANCE: BillingManager? = null
        fun get(ctx: Context): BillingManager =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: BillingManager(ctx.applicationContext).also { INSTANCE = it }
            }
    }

    sealed class BillingState {
        object Idle : BillingState()
        object Connecting : BillingState()
        object Ready : BillingState()          // connected, product info loaded
        data class PriceLoaded(val price: String) : BillingState()
        object Pending : BillingState()        // flow launched, waiting
        object Success : BillingState()        // purchase complete
        data class Failed(val msg: String) : BillingState()
    }

    private val _state = MutableStateFlow<BillingState>(BillingState.Idle)
    val state: StateFlow<BillingState> = _state.asStateFlow()

    private val client: BillingClient = BillingClient.newBuilder(context)
        .setListener(this)
        .enablePendingPurchases()
        .build()

    // Product details loaded after connection
    private var productDetails: ProductDetails? = null

    fun connect() {
        if (client.isReady) { _state.value = BillingState.Ready; return }
        _state.value = BillingState.Connecting
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(result: BillingResult) {
                if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                    loadProduct()
                } else {
                    _state.value = BillingState.Failed("Billing setup failed: ${result.debugMessage}")
                }
            }
            override fun onBillingServiceDisconnected() {
                _state.value = BillingState.Idle
            }
        })
    }

    private fun loadProduct() {
        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(listOf(
                QueryProductDetailsParams.Product.newBuilder()
                    .setProductId(PRODUCT_ID)
                    .setProductType(BillingClient.ProductType.INAPP)
                    .build()
            ))
            .build()
        client.queryProductDetailsAsync(params) { result, details ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK && details.isNotEmpty()) {
                productDetails = details[0]
                val price = details[0].oneTimePurchaseOfferDetails?.formattedPrice ?: PRICE_DISPLAY
                _state.value = BillingState.PriceLoaded(price)
            } else {
                // Product not found in Play Console (dev environment)
                _state.value = BillingState.Ready
            }
        }
    }

    /** Launch the billing flow. Returns false if not ready. */
    fun launchBillingFlow(activity: Activity): Boolean {
        val details = productDetails ?: run {
            _state.value = BillingState.Failed("PRODUCT_NOT_AVAILABLE")
            return false
        }
        if (!client.isReady) { connect(); return false }

        val params = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(listOf(
                BillingFlowParams.ProductDetailsParams.newBuilder()
                    .setProductDetails(details)
                    .build()
            ))
            .build()
        val result = client.launchBillingFlow(activity, params)
        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
            _state.value = BillingState.Pending
        } else {
            _state.value = BillingState.Failed(result.debugMessage)
        }
        return result.responseCode == BillingClient.BillingResponseCode.OK
    }

    override fun onPurchasesUpdated(result: BillingResult, purchases: List<Purchase>?) {
        when (result.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                purchases?.forEach { purchase ->
                    if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
                        acknowledgePurchase(purchase)
                    }
                }
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                _state.value = BillingState.Failed("USER_CANCELED")
            }
            else -> {
                _state.value = BillingState.Failed(result.debugMessage)
            }
        }
    }

    private fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.isAcknowledged) { _state.value = BillingState.Success; return }
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        client.acknowledgePurchase(params) { result ->
            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                _state.value = BillingState.Success
            } else {
                _state.value = BillingState.Failed("Acknowledge failed: ${result.debugMessage}")
            }
        }
    }

    fun resetState() { _state.value = BillingState.Idle }
}
