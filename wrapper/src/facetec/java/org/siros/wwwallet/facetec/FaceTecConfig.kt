package org.siros.wwwallet.facetec

import android.content.Context
import androidx.core.graphics.toColorInt
import com.facetec.sdk.FaceTecCancelButtonCustomization
import com.facetec.sdk.FaceTecCustomization
import com.facetec.sdk.FaceTecSDK
import com.facetec.sdk.FaceTecSecurityWatermarkImage
import org.json.JSONObject
import org.siros.wwwallet.R
import org.siros.wwwallet.logging.YOLOLogger
import org.siros.wwwallet.tagForLog

/**
 * Static FaceTec SDK configuration for the Photo ID Match flow.
 *
 * [DEVICE_KEY_IDENTIFIER] is the public production-key identifier issued by FaceTec for this
 * application (via the FaceTec Configuration Wizard) — it is not a secret and is meant to ship
 * inside the app, unlike the facetec-api bearer token in [org.siros.wwwallet.BuildConfig].
 */
object FaceTecConfig {
    const val DEVICE_KEY_IDENTIFIER = "dHrY0vwYRYn44JtJVHTNoBgvnaS5BGJw"

    private const val OCR_CUSTOMIZATION_ASSET = "FaceTec_OCR_Customization.json"

    fun customization(): FaceTecCustomization {
        val outerBackgroundColor = "#ffffff".toColorInt()
        val frameColor = "#ffffff".toColorInt()
        val borderColor = "#1c4587".toColorInt()
        val ovalColor = "#1c4587".toColorInt()
        val textColor = "#0c0e11".toColorInt()
        val buttonAndFeedbackBarColor = "#1c4587".toColorInt()
        val buttonAndFeedbackBarTextColor = "#ffffff".toColorInt()
        val buttonColorHighlight = "#3e6198".toColorInt()
        val buttonColorDisabled = "#414141".toColorInt()

        return FaceTecCustomization().apply {
            frameCustomization.cornerRadius = 20
            frameCustomization.backgroundColor = frameColor
            frameCustomization.borderColor = borderColor

            overlayCustomization.brandingImage = R.drawable.facetec_your_app_logo
            overlayCustomization.backgroundColor = outerBackgroundColor

            guidanceCustomization.backgroundColors = frameColor
            guidanceCustomization.foregroundColor = textColor
            guidanceCustomization.buttonBackgroundNormalColor = buttonAndFeedbackBarColor
            guidanceCustomization.buttonBackgroundDisabledColor = buttonColorDisabled
            guidanceCustomization.buttonBackgroundHighlightColor = buttonColorHighlight
            guidanceCustomization.buttonTextNormalColor = buttonAndFeedbackBarTextColor
            guidanceCustomization.buttonTextDisabledColor = buttonAndFeedbackBarTextColor
            guidanceCustomization.buttonTextHighlightColor = buttonAndFeedbackBarTextColor
            guidanceCustomization.retryScreenImageBorderColor = borderColor
            guidanceCustomization.retryScreenOvalStrokeColor = borderColor

            ovalCustomization.strokeColor = ovalColor
            ovalCustomization.progressColor1 = ovalColor
            ovalCustomization.progressColor2 = ovalColor

            feedbackCustomization.backgroundColors = buttonAndFeedbackBarColor
            feedbackCustomization.textColor = buttonAndFeedbackBarTextColor

            cancelButtonCustomization.customImage = R.drawable.facetec_cancel
            cancelButtonCustomization.location = FaceTecCancelButtonCustomization.ButtonLocation.TOP_LEFT

            resultScreenCustomization.backgroundColors = frameColor
            resultScreenCustomization.foregroundColor = textColor
            resultScreenCustomization.activityIndicatorColor = buttonAndFeedbackBarColor
            resultScreenCustomization.resultAnimationBackgroundColor = buttonAndFeedbackBarColor
            resultScreenCustomization.resultAnimationForegroundColor = buttonAndFeedbackBarTextColor
            resultScreenCustomization.uploadProgressFillColor = buttonAndFeedbackBarColor

            securityWatermarkImage = FaceTecSecurityWatermarkImage.FACETEC

            idScanCustomization.selectionScreenBackgroundColors = frameColor
            idScanCustomization.selectionScreenForegroundColor = textColor
            idScanCustomization.reviewScreenBackgroundColors = frameColor
            idScanCustomization.reviewScreenForegroundColor = buttonAndFeedbackBarTextColor
            idScanCustomization.reviewScreenTextBackgroundColor = buttonAndFeedbackBarColor
            idScanCustomization.captureScreenForegroundColor = buttonAndFeedbackBarTextColor
            idScanCustomization.captureScreenTextBackgroundColor = buttonAndFeedbackBarColor
            idScanCustomization.buttonBackgroundNormalColor = buttonAndFeedbackBarColor
            idScanCustomization.buttonBackgroundDisabledColor = buttonColorDisabled
            idScanCustomization.buttonBackgroundHighlightColor = buttonColorHighlight
            idScanCustomization.buttonTextNormalColor = buttonAndFeedbackBarTextColor
            idScanCustomization.buttonTextDisabledColor = buttonAndFeedbackBarTextColor
            idScanCustomization.buttonTextHighlightColor = buttonAndFeedbackBarTextColor
            idScanCustomization.captureScreenBackgroundColor = frameColor
            idScanCustomization.captureFrameStrokeColor = borderColor
        }
    }

    /**
     * Sets the localized group/field/placeholder strings for the ID Scan OCR Confirmation
     * Screen. Uses FaceTec's own template asset, same as their Sample App.
     */
    fun configureOCRLocalization(context: Context) {
        try {
            val json =
                context.assets.open(OCR_CUSTOMIZATION_ASSET).use { stream ->
                    JSONObject(stream.bufferedReader().readText())
                }

            FaceTecSDK.configureOCRLocalization(json)
        } catch (e: Exception) {
            YOLOLogger.e(tagForLog, "Failed to load FaceTec OCR localization asset.", e)
        }
    }
}
