package com.drivepet.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import androidx.core.graphics.PathParser
import kotlin.math.max
import kotlin.math.sin

/**
 * Draws the pet from the designer's character sheets (알 / 유아기 / 반항기 / 기본, and the 화남 / 배고픔 / 다침 faces):
 * a honey bear with a big, slightly tilted head, flat colours and a cocoa outline. The shapes are the designer's own
 * paths, kept in the coordinates of the 512 x 512 artboard ("art pixels") and only moved and scaled when drawn.
 *
 * The caller draws in body units: 1 = half the width of the grown bear's head, the pet stands on y = 0 and is centred
 * on x = 0. [height] and [halfWidth] say how big each growth stage is in those units.
 *
 * The limbs are separate shapes, so the grown bear really walks (the legs take turns, the arms swing, the head sways).
 * Hats and glasses are still drawn in the old "head box" (2 wide, 2 * [K] tall) and are placed on each stage's head
 * with [HAT] and [EYES], so they sit right on every stage and follow the head's tilt.
 */
class PetPainter {
    companion object {
        /** the proportions of the box hats and glasses are drawn in (height / width of the old round head) */
        const val K = 0.833f
        /** art pixels per body unit: half the width of the grown bear's head */
        const val PX = 106.5f

        /** how tall the pet is, ears included, in body units */
        fun height(stage: GrowthStage) = when (stage) { GrowthStage.EGG -> 2.61f; GrowthStage.INFANT -> 2.13f; GrowthStage.REBEL -> 2.49f; GrowthStage.ADULT -> 2.69f }
        /** half the pet's width, in body units */
        fun halfWidth(stage: GrowthStage) = when (stage) { GrowthStage.EGG -> 1.06f; GrowthStage.INFANT -> 0.9f; GrowthStage.REBEL -> 0.95f; GrowthStage.ADULT -> 1f }
        /** how high the middle of the face is, in body units (where a speech bubble points) */
        fun faceY(stage: GrowthStage) = when (stage) { GrowthStage.EGG -> 1.75f; GrowthStage.INFANT -> 1.35f; GrowthStage.REBEL -> 1.65f; GrowthStage.ADULT -> 1.8f }

        private const val HONEY = 0xFFF7C873.toInt()
        private const val BUTTER = 0xFFF9E092.toInt()
        private const val BLUSH = 0xFFF59A7A.toInt()
        private const val SKY = 0xFF96BFF8.toInt()
        private const val CREAM = 0xFFFFF9F1.toInt()
        private const val ANGER = 0xFFF86548.toInt()

        private fun p(d: String): Path = PathParser.createPathFromPathData(d)
    }

    /** 0..1: how wide the pet is yawning right now (set by the view before drawing) */
    var yawn = 0f
    /** false: the egg of the very first drives, still whole: no face, no ears and no crack yet (see [GrowthStage.EGG_WAKES_KM]) */
    var eggAwake = true

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val stitch = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private val tmp = Path()

    // ================================================================== the designer's shapes, in art pixels

    /** the colours of one mood: the hurt bear is grey, as on the designer's sheet */
    private class Tone(val body: Int, val belly: Int, val earIn: Int, val shell: Int, val paw: Int)
    private val warm = Tone(HONEY, BUTTER, BLUSH, BUTTER, HONEY)
    private val grey = Tone(0xFFB3B5C2.toInt(), 0xFFC4C6D3.toInt(), 0xFFC4C6D3.toInt(), 0xFFCFD1DC.toInt(), 0xFFB3B5C2.toInt())
    private fun tone(st: PetState) = if (st == PetState.HURT) grey else warm

    /** where one stage stands on the artboard and where its head is */
    private class Frame(val cx: Float, val ground: Float, val x: Float, val y: Float, val deg: Float, val scale: Float, val eyeX: Float = 0f)

    // ---- the grown bear (기본캐릭터)
    private val aArmL = p("M224.84,349.42c0,11.59-9.41,21-21,21-10.05,0-19.23-7.03-21.29-16.45-.96-3.95-1.39-9.11-1.17-14.84.85-21.65,36.37-53.5,39.71-46.21,4.48,9.76-1.89,25.88-.23,44.2C221.75,342.68,224.84,344.83,224.84,349.42Z")
    private val aArmR = p("M286.58,347.29c0,11.59,9.41,21,21,21,10.05,0,19.24-7.03,21.3-16.45.95-3.95,1.38-9.11,1.16-14.84-.85-21.65-36.37-53.5-39.71-46.21-4.48,9.77,1.89,25.88.23,44.2C289.68,340.55,286.58,342.7,286.58,347.29Z")
    private val legL = p("M256.58,357.46v14.28c0,8.29-6.72,15-15,15s-15-6.71-15-15v-24.19c0-1.22.14-2.4.42-3.53C234.53,353.15,245.59,357.63,256.58,357.46Z")
    private val legR = p("M286.58,347.55v24.19c0,8.29-6.72,15-15,15s-15-6.71-15-15v-14.28c11.03-.16,22-5,29.27-14.52C286.33,344.39,286.58,345.94,286.58,347.55Z")
    private val torso = p("M296.59,315.75c3.69,25-18.17,43.54-40.59,43.54s-44.28-18.54-40.59-43.54,18.17-43.55,40.59-43.55S293.08,291.96,296.59,315.75Z")
    private val belly = p("M283.5,309.94c2.5,16.94-12.31,29.5-27.5,29.5s-30-12.56-27.5-29.5,12.31-29.5,27.5-29.5S281.12,293.82,283.5,309.94Z")
    private val aHead = p("M149.5,206.46c-.64-33.9,17.47-57.89,43.36-71.97-1.76-2.94-2.77-6.56-2.71-10.86-.39-26.37,39.86-27.1,41.73-2.2,25.48-4.25,52.91-1.66,76.02,7.75,7.71-18.02,40.53-15.12,40.18,8.7.09,6.29-2.13,11.12-5.59,14.49,12.72,13.73,20.43,31.77,20.01,54.1C364.68,322.52,147.3,322.52,149.5,206.46Z")
    private val aEarL = p("M221.45,124.62c1.19,5.82-4.5,8.46-10.68,10.67-5.35,1.92-10.68-4.78-10.68-10.67s4.79-10.68,10.68-10.68S220.27,118.84,221.45,124.62Z")
    private val aEarR = p("M317.15,134.14c-2.93,5.17,1.67,9.44,6.86,13.45,4.5,3.48,11.63-1.24,13.45-6.85s-1.24-11.63-6.85-13.46S320.06,129.01,317.15,134.14Z")
    private val aSmile = p("M252.04,236.41c10.42,3.96,23.38-.66,28.93-10.33")

    // ---- the rebel (반항기): the same body with shorter arms, a slightly smaller head, a fringe over one eye and a pout
    private val rArmL = p("M228.53,347.11c0,10.21-8.28,18.49-18.49,18.49-8.85,0-16.94-6.19-18.75-14.48-.84-3.49-1.22-8.03-1.03-13.07.75-19.07,32.03-47.11,34.97-40.69,3.94,8.6-1.66,22.79-.2,38.92C225.81,341.17,228.53,343.07,228.53,347.11Z")
    private val rArmR = p("M282.89,345.24c0,10.2,8.29,18.49,18.49,18.49,8.85,0,16.94-6.19,18.76-14.49.84-3.48,1.22-8.02,1.02-13.07-.75-19.06-32.02-47.1-34.97-40.69-3.94,8.6,1.67,22.79.21,38.92C285.62,339.3,282.89,341.19,282.89,345.24Z")
    private val rHead = p("M155.52,221.35c-.61-31.98,16.48-54.62,40.91-67.9-1.66-2.78-2.62-6.19-2.56-10.25-.37-24.88,37.61-25.57,39.37-2.07,24.04-4.01,49.92-1.57,71.73,7.31,7.27-17,38.24-14.27,37.91,8.21.08,5.93-2.01,10.49-5.28,13.67,12.01,12.95,19.28,29.97,18.88,51.04C358.54,330.86,153.44,330.86,155.52,221.35Z")
    private val rEarL = p("M223,142.03c1.19,5.83-4.51,8.47-10.68,10.68-5.35,1.92-10.68-4.78-10.68-10.68s4.78-10.67,10.68-10.67S221.82,136.26,223,142.03Z")
    private val rEarR = p("M312.89,153.11c-2.93,5.17,1.67,9.44,6.86,13.45,4.5,3.48,11.63-1.24,13.46-6.85s-1.25-11.64-6.86-13.46S315.81,147.98,312.89,153.11Z")
    private val rEye = p("M316.8,217.58c0,4.14-4.47,7.5-10,7.5s-10-3.36-10-7.5,4.48-6.66,10-6.66S316.8,213.44,316.8,217.58Z")
    private val rPout = p("M247.29,238.11c-3.22-2.45-8.63-.92-10.09,2.86s1.51,8.55,5.55,8.9c-3.67,1.34-5.54,6.22-3.71,9.67s6.91,4.65,10.09,2.37")
    private val rFringe = p("M157.97,239c18.34-5.68,34.29-9.17,53.26-12.14.12,3.52-.51,7.06-1.82,10.32q26.84-19.83,54.76-38.14-.24,9.52-.47,19.05c3.06-4.35,10.52-6.75,14.98-9.65,14.11-9.17,22.28-23.56,25.35-39.44")

    // ---- the baby (유아기): sitting, with a bib and a dummy
    private val iArmL = p("M230.93,334.59c0,7.97-7.53,14.43-16.8,14.43-8.04,0-15.39-4.83-17.04-11.3-.76-2.72-1.11-6.27-.93-10.2.68-14.89,29.09-36.78,31.77-31.77,3.58,6.71-1.52,17.79-.19,30.38C228.45,329.96,230.93,331.43,230.93,334.59Z")
    private val iArmR = p("M277.9,331.81c0,7.97,7.53,14.44,16.8,14.44,8.04,0,15.39-4.84,17.03-11.31.77-2.72,1.11-6.27.93-10.2-.68-14.89-29.09-36.77-31.76-31.76-3.58,6.71,1.51,17.78.18,30.38C280.38,327.18,277.9,328.66,277.9,331.81Z")
    private val iLegL = Path().apply { addRoundRect(RectF(184.4f, 326.18f, 242.65f, 356.18f), 15f, 15f, Path.Direction.CW) }
    private val iLegR = Path().apply { addRoundRect(RectF(228.6f, 329.39f, 286.85f, 359.39f), 15f, 15f, Path.Direction.CW) }
    private val iTorso = p("M293.91,312.65c3.42,23.17-16.85,40.36-37.62,40.36s-41.04-17.19-37.62-40.36,16.84-40.36,37.62-40.36S290.65,290.6,293.91,312.65Z")
    private val iBelly = p("M271.87,309c1.98,15.7-9.75,27.35-21.78,27.35s-23.76-11.65-21.78-27.35c1.89-14.93,9.76-27.34,21.78-27.34S269.99,294.07,271.87,309Z")
    private val iBib = Path().apply { addOval(RectF(221.61f, 255.48f, 283.61f, 320.48f), Path.Direction.CW) }
    private val iHead = p("M163.01,220.4c-.55-27.98,15.26-47.78,37.86-59.4-1.53-2.43-2.42-5.42-2.36-8.96-.34-21.77,34.8-22.37,36.43-1.82,22.25-3.51,46.2-1.37,66.37,6.4,6.74-14.88,35.39-12.48,35.09,7.18.07,5.19-1.86,9.18-4.89,11.96,11.11,11.33,17.84,26.22,17.48,44.65C350.89,316.19,161.09,316.19,163.01,220.4Z")
    private val iEarL = p("M225.85,152c1.1,5.36-4.15,7.79-9.83,9.83-4.93,1.76-9.83-4.4-9.83-9.83s4.4-9.83,9.83-9.83S224.76,146.68,225.85,152Z")
    private val iEarR = p("M307.67,160.64c-2.69,4.76,1.54,8.7,6.31,12.39,4.15,3.2,10.71-1.15,12.39-6.31,1.68-5.16-1.15-10.71-6.31-12.39C314.9,152.66,310.35,155.92,307.67,160.64Z")
    private val iDummy = p("M275.61,248.71c-.86,5.13-4.71,9.14-9.87,11.04,0,0,0,0-.02,0l-16.57,10.95c-4.7,3.1-12.11,1.87-15.54-2.59l-12.13-15.72s-.02-.02-.02-.02c-4.27-3.47-6.61-8.51-5.76-13.64,1.37-8.22,10.43-13.56,20.24-11.93,4.72.79,8.76,3.06,11.52,6.11,3.6-1.99,8.15-2.83,12.87-2.04C270.14,232.5,276.98,240.49,275.61,248.71Z")

    // ---- the egg (알): ears and a face already, a crack round the middle and a paw print
    private val eShell = p("M368.5,298.89c0,59.1-50.37,107-112.5,107s-112.5-47.9-112.5-107c0-46.37,18.72-95.52,52.49-128.43-5.13-4.12-8.41-10.44-8.41-17.52,0-12.4,10.05-22.45,22.45-22.45,9.74,0,18.02,6.2,21.13,14.86,9.22-4.52,19.06-7.76,29.26-8.59,17.73-1.44,34.83,4.66,49.36,15.06,4.09-5.44,10.6-8.95,17.93-8.95,12.4,0,22.45,10.05,22.45,22.45,0,8.14-4.33,15.27-10.81,19.21C358.22,216.64,368.5,261.88,368.5,298.89Z")
    // the same shell before the ears have come out (the designer's egg with the two ear bumps smoothed away)
    private val eWhole = p("M368.5,298.89c0,59.1-50.37,107-112.5,107s-112.5-47.9-112.5-107c0-46.37,18.72-95.52,52.49-128.43C206,160.5,218,151.5,231.16,145.35c9.22-4.52,19.06-7.76,29.26-8.59,17.73-1.44,34.83,4.66,49.36,15.06C321,160,331,171.5,339.35,184.53C358.22,216.64,368.5,261.88,368.5,298.89Z")
    private val eSmile = p("M248.54,229c9.14,6.36,22.84,5.01,30.56-3.02")
    private val eCrack = p("M145.64,290.78L183.76,274.92L219.16,297.62L257.28,281.74L292.69,304.44L330.96,288.64L366.36,311.33")
    private val ePaw = p("M242.61,342.29c3.19-4.85,9.36-7.26,16.12-6.98,0,0,.02.01.03,0l23.81-3.99c6.75-1.13,14.76,3.31,16.8,9.31l7.21,21.18s.02.02.02.02c3.42,5.36,3.95,11.48.76,16.33-5.12,7.76-17.86,9.29-28.46,3.42-5.1-2.83-8.78-6.86-10.65-11.15-5.01.48-10.61-.63-15.71-3.46C241.94,361.1,237.49,350.05,242.61,342.29Z")
    private val ePawToe = p("M309.44,323.95c1.42,5.02-2.55,8.73-8.86,8.27-6.32-.45-12.6-4.89-14.02-9.92-1.42-5.03,2.55-8.73,8.86-8.28C301.74,314.48,308.02,318.92,309.44,323.95Z")

    // ---- the faces of the three bad moods, drawn for the grown bear and moved onto the other stages with [faceOf]
    private val angryEyeR = p("M326.2,196.15c.72,2.42-4.27,5.39-11.14,6.63-6.86,1.24-13.01.29-13.72-2.13-.71-2.42,4.27-5.4,11.14-6.64S325.49,193.73,326.2,196.15Z")
    private val angryEyeL = p("M187.31,180.26c-1.56,1.98,1.95,6.6,7.85,10.32,5.9,3.73,11.96,5.14,13.53,3.16,1.56-1.98-1.95-6.6-7.85-10.32S188.88,178.28,187.31,180.26Z")
    private val angryBrow = p("M291.25,188.12c3.89-3.32,8.38-5.94,13.18-7.71")
    private val angryPout = p("M242.38,209.87c-3.22-2.46-8.63-.93-10.09,2.85s1.51,8.55,5.55,8.9c-3.68,1.35-5.54,6.22-3.71,9.68s6.91,4.64,10.09,2.36")
    private val angryMark = listOf(
        p("M334.16,166.53c4.73,2.79,7.76,8.25,7.64,13.74-.13,5.49-3.4,10.8-8.25,13.37"),
        p("M345.2,159.54c.66,6.29,5.62,11.89,11.78,13.3s13.07-1.47,16.39-6.84"),
        p("M339.53,202.87c2.37-5.58,9.07-8.92,14.95-7.45s10.23,7.54,9.72,13.58"),
        p("M376.33,176.91c-5.36.87-9.74,5.92-9.84,11.35s4.07,10.65,9.39,11.73"),
    )
    private val hungryLineR = p("M329.24,215.59c-6.97-2.57-15.26-1.64-21.39,2.4-6.14,4.04-9.94,11.08-9.82,18.16")
    private val hungryLineL = p("M180.52,199.28c14.87,3.65,25.27,16.56,23.73,29.42")
    private val hurtEyeR = p("M325.53,186.21q-9.49,6.24-18.99,12.47,8.39,7.7,17.15,14.96")
    private val hurtEyeL = p("M189.11,173.7l16.26,15.86q-9.7,5.96-19.69,11.42")
    private val hurtMouth = p("M224.5,226.77c-.14-4.91,5.4-8.78,9.37-6.56,3.21,1.8,5.71,6.64,9.14,5.39,1.26-.46,2.14-1.68,3.13-2.65,3.62-3.53,9.59-3.67,13.34-.31,2.38,2.13,4.63,5.7,7.61,4.89,1.67-.46,2.74-2.21,4.08-3.4,4.04-3.6,11.19-.85,12.33,4.74")
    private val tear = p("M323.13,247.61c0,9.12-5.85,16.52-13.07,16.52s-13.06-7.4-13.06-16.52,5.94-27.96,13.15-28.46C316.22,218.72,323.13,238.48,323.13,247.61Z")
    private val plasterPad = p("M322.94,185.71l-25.98-28.35c-1.79-1.95-1.76-4.82.07-6.39l15.67-13.52c1.83-1.58,4.76-1.28,6.55.67l25.98,28.36c1.79,1.95,1.76,4.81-.07,6.39l-15.67,13.52C327.66,187.96,324.73,187.66,322.94,185.71Z")

    // ---- where each stage stands, where a hat sits on it (the top of the head, the head's tilt and its size) and where
    //      its eyes are (for glasses). All in art pixels; eyeX is half the distance between the eyes in head-box units.
    private val STAND = mapOf(
        GrowthStage.ADULT to Frame(256.5f, 389.2f, 0f, 0f, 0f, 0f),
        GrowthStage.REBEL to Frame(256.5f, 389.2f, 0f, 0f, 0f, 0f),
        GrowthStage.INFANT to Frame(256.5f, 361.9f, 0f, 0f, 0f, 0f),
        GrowthStage.EGG to Frame(256f, 408.4f, 0f, 0f, 0f, 0f),
    )
    private val HAT = mapOf(
        GrowthStage.ADULT to Frame(0f, 0f, 268.5f, 124f, 6f, 106.5f),
        GrowthStage.REBEL to Frame(0f, 0f, 267.8f, 143.5f, 6f, 100.5f),
        GrowthStage.INFANT to Frame(0f, 0f, 267f, 152.5f, 5.7f, 93f),
        GrowthStage.EGG to Frame(0f, 0f, 268.5f, 141f, 6f, 104f),
    )
    private val EYES = mapOf(
        GrowthStage.ADULT to Frame(0f, 0f, 255.5f, 192.9f, 5.4f, 106.5f, 0.549f),
        GrowthStage.REBEL to Frame(0f, 0f, 254.3f, 210.6f, 5.4f, 100.5f, 0.549f),
        GrowthStage.INFANT to Frame(0f, 0f, 254.45f, 220.3f, 5.4f, 100f, 0.585f),
        GrowthStage.EGG to Frame(0f, 0f, 260.8f, 210.5f, 7f, 100f, 0.471f),
    )
    /** moves a face drawn for the grown bear onto another stage's head */
    private val faceOf = mapOf(
        GrowthStage.ADULT to Matrix(),
        GrowthStage.REBEL to Matrix().apply { setScale(0.9435f, 0.9435f); postTranslate(14.47f, 26.56f) },
        GrowthStage.INFANT to Matrix().apply { setTranslate(-1.05f, 27.37f) },
        GrowthStage.EGG to Matrix().apply { setScale(0.8085f, 0.8085f, 255.5f, 192.9f); postTranslate(5.27f, 17.57f) },
    )
    /** the point the head turns about when it sways (the neck) */
    private val NECK = mapOf(
        GrowthStage.ADULT to floatArrayOf(256f, 286f), GrowthStage.REBEL to floatArrayOf(256f, 296f),
        GrowthStage.INFANT to floatArrayOf(256f, 286f), GrowthStage.EGG to floatArrayOf(256f, 408f),
    )

    // ---- trousers: the part on the belly (under a belt that curves with it) and the part on each leg, down to the hem
    private val waist = Path().apply { moveTo(196f, 323f); quadTo(256f, 339f, 316f, 323f); lineTo(316f, 400f); lineTo(196f, 400f); close() }
    private val beltLine = Path().apply { moveTo(196f, 326f); quadTo(256f, 342f, 316f, 326f) }
    private fun hem(id: String) = if (id == "jeans") 379.5f else 367f
    private val pantsShape = HashMap<String, Path>()
    /** the whole pair as one outline (for the shop picture) */
    private fun pantsOutline(id: String): Path = pantsShape.getOrPut(id) {
        val top = Path().apply { op(torso, waist, Path.Op.INTERSECT) }
        val cut = Path().apply { addRect(200f, 300f, 312f, hem(id), Path.Direction.CW) }
        val l = Path().apply { op(legL, cut, Path.Op.INTERSECT) }
        val r = Path().apply { op(legR, cut, Path.Op.INTERSECT) }
        Path().apply { op(top, l, Path.Op.UNION); op(r, Path.Op.UNION) }
    }

    // ================================================================== drawing

    // the white rim of the small pet over other apps: the silhouette is stroked wide twice (a soft shadow, then white)
    // before the pet itself is drawn. 0 and 1 are those two passes, 2 is the real drawing.
    private var pass = 2
    private var rimW = 0f

    /** a body part: filled and outlined (or only its rim in the first two passes) */
    private fun solid(cv: Canvas, path: Path, color: Int, w: Float) {
        if (pass < 2) {
            line.color = if (pass == 0) 0x40281E14 else Look.WHITE; line.strokeWidth = if (pass == 0) rimW * 1.28f else rimW
            cv.drawPath(path, line)
            if (pass == 1) { fill.shader = null; fill.color = Look.WHITE; cv.drawPath(path, fill) }
            return
        }
        fill.shader = null; fill.color = color; cv.drawPath(path, fill)
        line.color = Look.COCOA; line.strokeWidth = w; cv.drawPath(path, line)
    }
    private fun flat(cv: Canvas, path: Path, color: Int) { fill.shader = null; fill.color = color; cv.drawPath(path, fill) }
    private fun stroke(cv: Canvas, path: Path, w: Float, color: Int = Look.COCOA) { line.color = color; line.strokeWidth = w; cv.drawPath(path, line) }
    private fun oval(cx: Float, cy: Float, rx: Float, ry: Float) = rect.apply { set(cx - rx, cy - ry, cx + rx, cy + ry) }
    private fun blob(cv: Canvas, cx: Float, cy: Float, rx: Float, ry: Float, deg: Float, color: Int) {
        cv.save(); cv.rotate(deg, cx, cy); fill.shader = null; fill.color = color; cv.drawOval(oval(cx, cy, rx, ry), fill); cv.restore()
    }
    private fun dot(cv: Canvas, cx: Float, cy: Float, r: Float, color: Int = Look.COCOA) { fill.shader = null; fill.color = color; cv.drawCircle(cx, cy, r, fill) }

    /**
     * The pet, in body units, standing on y = 0 of the current canvas.
     * [ow] is the outline width in body units; [sticker] adds a white rim around the whole silhouette (the small pet over
     * other apps, so it reads on any map). [stepT] is the phase of the walk (one full turn = both feet).
     */
    fun draw(cv: Canvas, st: PetState, stage: GrowthStage, outfitIn: Map<Slot, String>, back: Boolean, walking: Boolean, stepT: Float, ow: Float, now: Long, sticker: Boolean) {
        // an egg and a baby cannot wear trousers (hats and glasses are fine)
        val outfit = if ((stage == GrowthStage.EGG || stage == GrowthStage.INFANT) && Slot.PANTS in outfitIn) outfitIn - Slot.PANTS else outfitIn
        val f = STAND.getValue(stage)
        val w = ow * PX
        cv.save()
        cv.scale(1f / PX, 1f / PX)
        if (back) cv.scale(-1f, 1f)   // seen from behind: the same outline the other way round, and no face
        cv.translate(-f.cx, -f.ground)
        val step = if (walking) sin(stepT) else 0f
        if (sticker) {
            rimW = w * 3.4f
            for (i in 0..1) { pass = i; figure(cv, st, stage, outfit, back, step, w, now) }
        }
        pass = 2
        figure(cv, st, stage, outfit, back, step, w, now)
        cv.restore()
    }

    private fun figure(cv: Canvas, st: PetState, stage: GrowthStage, outfit: Map<Slot, String>, back: Boolean, step: Float, w: Float, now: Long) {
        val t = tone(st)
        val real = pass == 2
        val neck = NECK.getValue(stage)
        when (stage) {
            GrowthStage.ADULT, GrowthStage.REBEL -> {
                val rebel = stage == GrowthStage.REBEL
                // the arms hang behind the body and swing from the shoulder with each step
                cv.save(); cv.rotate(step * 13f, 219f, 298f); solid(cv, if (rebel) rArmL else aArmL, t.body, w); cv.restore()
                cv.save(); cv.rotate(step * 13f, 293f, 296f); solid(cv, if (rebel) rArmR else aArmR, t.body, w); cv.restore()
                // the legs take turns: each one is lifted (tucked up under the body) for half of the step
                val pants = outfit[Slot.PANTS]
                leg(cv, legL, max(0f, step), -1f, t, pants, w)
                leg(cv, legR, max(0f, -step), 1f, t, pants, w)
                if (real) {
                    flat(cv, torso, t.body)
                    if (!back) flat(cv, belly, t.belly)
                    if (pants != null) pantsTop(cv, pants, back, w)
                    stroke(cv, torso, w)
                    if (back) tail(cv, 256f, 337f, t, w)
                    if (st == PetState.HUNGRY && !back) rumble(cv, 166f, 346f, 322f, w, now)
                } else solid(cv, torso, t.body, w)
                cv.save(); cv.rotate(-step * 1.6f, neck[0], neck[1])
                solid(cv, if (rebel) rHead else aHead, t.body, w)
                if (real && !back) {
                    flat(cv, if (rebel) rEarL else aEarL, t.earIn); flat(cv, if (rebel) rEarR else aEarR, t.earIn)
                    face(cv, st, stage, w, now)
                }
                wear(cv, stage, outfit, back, w)
                cv.restore()
            }
            GrowthStage.INFANT -> {
                solid(cv, iArmL, t.body, w); solid(cv, iLegL, t.body, w)
                if (real) {
                    flat(cv, iTorso, t.body); if (!back) flat(cv, iBelly, t.belly); stroke(cv, iTorso, w)
                    if (back) tail(cv, 256f, 330f, t, w) else solid(cv, iBib, CREAM, w)
                    if (st == PetState.HUNGRY && !back) rumble(cv, 170f, 336f, 318f, w, now)
                } else solid(cv, iTorso, t.body, w)
                solid(cv, iLegR, t.body, w); solid(cv, iArmR, t.body, w)
                solid(cv, iHead, t.body, w)
                if (real && !back) {
                    flat(cv, iEarL, t.earIn); flat(cv, iEarR, t.earIn)
                    face(cv, st, stage, w, now)
                }
                wear(cv, stage, outfit, back, w)
            }
            GrowthStage.EGG -> {
                solid(cv, if (eggAwake) eShell else eWhole, t.shell, w)
                if (real) {
                    if (!back) {
                        flat(cv, ePaw, t.paw); flat(cv, ePawToe, t.paw)
                        blob(cv, 271f, 321f, 13.42f, 7.48f, -5.05f, t.paw); blob(cv, 311.9f, 345.05f, 7.48f, 13.42f, -15.39f, t.paw)
                        if (eggAwake) face(cv, st, stage, w, now)
                    }
                    if (eggAwake) stroke(cv, eCrack, w)
                }
                wear(cv, stage, if (eggAwake) outfit else outfit - Slot.GLASSES, back, w)   // no eyes yet, so no glasses
            }
        }
    }

    private fun leg(cv: Canvas, path: Path, lift: Float, side: Float, t: Tone, pants: String?, w: Float) {
        cv.save()
        cv.translate(side * lift * 1.5f, -lift * 11f)
        if (pass < 2 || pants == null) solid(cv, path, t.body, w)
        else {
            flat(cv, path, t.body)
            val jeans = pants == "jeans"; val h = hem(pants)
            cv.save(); cv.clipPath(path)
            fill.shader = null; fill.color = if (jeans) JEANS else SHORTS; cv.drawRect(200f, 300f, 312f, h, fill)
            if (jeans) { fill.color = JEANS_CUFF; cv.drawRect(200f, h - 6.5f, 312f, h, fill); line.color = Look.COCOA; line.strokeWidth = w * 0.55f; cv.drawLine(200f, h - 6.5f, 312f, h - 6.5f, line) }
            line.color = Look.COCOA; line.strokeWidth = w * 0.8f; cv.drawLine(200f, h, 312f, h, line)
            cv.restore()
            stroke(cv, path, w)
        }
        cv.restore()
    }

    /** the trousers on the belly: the colour below a belt that curves with it, cut to the body's own outline */
    private fun pantsTop(cv: Canvas, id: String, back: Boolean, w: Float) {
        val jeans = id == "jeans"
        cv.save(); cv.clipPath(torso)
        flat(cv, waist, if (jeans) JEANS else SHORTS)
        stitch.color = if (jeans) 0xFFC9D7F7.toInt() else 0xFFE6F4E6.toInt(); stitch.strokeWidth = w * 0.4f
        stitch.pathEffect = DashPathEffect(floatArrayOf(w * 0.9f, w * 0.9f), 0f)
        cv.drawLine(256.4f, 341f, 256.4f, 360f, stitch)
        line.strokeCap = Paint.Cap.BUTT
        line.color = Look.COCOA; line.strokeWidth = 7f + w * 1.1f; cv.drawPath(beltLine, line)
        line.color = if (jeans) JEANS_BELT else SHORTS_BELT; line.strokeWidth = 7f; cv.drawPath(beltLine, line)
        line.strokeCap = Paint.Cap.ROUND
        if (!back) dot(cv, 256f, 334f, 2.6f, if (jeans) 0xFFF6C760.toInt() else Look.WHITE)
        cv.restore()
    }

    /** the hungry pet's tummy rumbles: two little waves on each side of the belly, growing and fading */
    private fun rumble(cv: Canvas, leftX: Float, rightX: Float, y: Float, w: Float, now: Long) {
        val ph = (now % 900L) / 900f
        line.color = 0xFFC79A3A.toInt(); line.strokeWidth = w * 0.8f
        for (k in 0..1) {
            val grow = ((ph + k * 0.5f) % 1f)
            line.alpha = (255 * (1f - grow)).toInt().coerceIn(0, 255)
            val rx = 5f + grow * 9f; val ry = 9f + grow * 5f
            cv.drawArc(oval(leftX - grow * 10f, y, rx, ry), 110f, 140f, false, line)
            cv.drawArc(oval(rightX + grow * 10f, y, rx, ry), -70f, 140f, false, line)
        }
        line.alpha = 255
    }

    private fun tail(cv: Canvas, x: Float, y: Float, t: Tone, w: Float) {
        fill.shader = null; fill.color = t.belly; cv.drawCircle(x, y, 10f, fill)
        line.color = Look.COCOA; line.strokeWidth = w; cv.drawCircle(x, y, 10f, line)
    }

    /** glasses and a hat, placed on this stage's head */
    private fun wear(cv: Canvas, stage: GrowthStage, outfit: Map<Slot, String>, back: Boolean, w: Float) {
        val g = outfit[Slot.GLASSES]
        if (g != null && pass == 2) {
            val e = EYES.getValue(stage)
            cv.save(); cv.translate(e.x, e.y); cv.rotate(e.deg); cv.scale(e.scale, e.scale); cv.translate(0f, 1.3f * K)
            glasses(cv, g, back, w / e.scale, e.eyeX)
            cv.restore()
        }
        val hatId = outfit[Slot.HAT] ?: return
        val h = HAT.getValue(stage)
        cv.save(); cv.translate(h.x, h.y); cv.rotate(h.deg); cv.scale(h.scale, h.scale); cv.translate(0f, 2f * K)
        if (pass == 2) hat(cv, hatId, w / h.scale)
        else {
            line.color = if (pass == 0) 0x40281E14 else Look.WHITE; line.strokeWidth = (if (pass == 0) rimW * 1.28f else rimW) / h.scale
            hatSilhouette(cv, hatId)
        }
        cv.restore()
    }

    // ------------------------------------------------------------------ faces

    private fun face(cv: Canvas, st: PetState, stage: GrowthStage, w: Float, now: Long) {
        val rebel = stage == GrowthStage.REBEL; val infant = stage == GrowthStage.INFANT; val egg = stage == GrowthStage.EGG
        // cheeks: the calm and the angry bear blush; the hungry and the hurt one are pale
        if (st == PetState.NORMAL || st == PetState.ANGRY) when (stage) {
            GrowthStage.ADULT -> { blob(cv, 177.81f, 207.34f, 18.77f, 12.34f, -2f, BLUSH); blob(cv, 327.11f, 221.74f, 18.77f, 12.34f, 7f, BLUSH) }
            GrowthStage.REBEL -> blob(cv, 324.66f, 237f, 18.77f, 12.34f, 7f, BLUSH)
            GrowthStage.INFANT -> { blob(cv, 191.94f, 241.3f, 18.77f, 12.34f, 13f, BLUSH); blob(cv, 308.85f, 251.75f, 18.77f, 12.34f, 7f, BLUSH) }
            GrowthStage.EGG -> {}
        }
        if (st == PetState.NORMAL) calm(cv, stage, w, now)
        else {
            cv.save(); cv.concat(faceOf.getValue(stage))
            val left = !rebel      // the rebel's other eye is under its fringe
            val mouth = !infant    // the baby's mouth is behind its dummy
            when (st) {
                PetState.ANGRY -> {
                    flat(cv, angryEyeR, Look.COCOA); if (left) flat(cv, angryEyeL, Look.COCOA)
                    stroke(cv, angryBrow, w * 0.8f)
                    if (mouth && !rebel) stroke(cv, angryPout, w)
                    // the anger mark throbs a little
                    val k = 1f + 0.08f * sin(now / 140f)
                    cv.save(); cv.scale(k, k, 355f, 184f); for (m in angryMark) stroke(cv, m, w, ANGER); cv.restore()
                }
                PetState.HUNGRY -> {
                    dot(cv, 313.77f, 198.4f, 10f); if (left) dot(cv, 197.26f, 187.4f, 10f)
                    stroke(cv, hungryLineR, w); if (left) stroke(cv, hungryLineL, w)
                    if (!egg) blob(cv, 256f, 194.67f, 33.5f, 25.5f, 0f, 0xFFE7B662.toInt())
                    if (mouth && !rebel) { fill.shader = null; fill.color = Look.COCOA; cv.drawOval(oval(251.21f, 220.17f, 21.1f, 5f), fill); line.color = Look.COCOA; line.strokeWidth = w; cv.drawOval(rect, line) }
                }
                PetState.HURT -> {
                    if (!egg) blob(cv, 256f, 194.67f, 33.5f, 25.5f, 0f, 0xFFA9A7A9.toInt())
                    stroke(cv, hurtEyeR, w * 1.2f); if (left) stroke(cv, hurtEyeL, w * 1.2f)
                    if (mouth && !rebel) stroke(cv, hurtMouth, w * 1.2f)
                    // the tear wells up and rolls a little way down
                    val drop = (now % 1800L) / 1800f
                    cv.save(); cv.translate(0f, drop * 7f); cv.scale(0.8f + 0.2f * drop, 0.8f + 0.2f * drop, 310f, 220f)
                    flat(cv, tear, SKY); stroke(cv, tear, w); cv.restore()
                }
                else -> {}
            }
            cv.restore()
            // the rebel keeps its own mouth (it sits beside the fringe), a little changed by the mood
            if (rebel) when (st) {
                PetState.HUNGRY -> { fill.shader = null; fill.color = Look.COCOA; cv.drawOval(oval(247f, 250f, 15f, 4.4f), fill); line.color = Look.COCOA; line.strokeWidth = w; cv.drawOval(rect, line) }
                PetState.HURT -> { cv.save(); cv.translate(-8f, 26f); cv.scale(0.8f, 0.8f, 255f, 225f); stroke(cv, hurtMouth, w * 1.3f); cv.restore() }
                else -> stroke(cv, rPout, w)
            }
        }
        if (rebel) stroke(cv, rFringe, w)
        if (infant) dummy(cv, w)
        if (st == PetState.HURT) {
            cv.save(); cv.concat(faceOf.getValue(stage))
            cv.save(); cv.rotate(-43.86f, 320.85f, 161.73f)
            rect.set(298.93f, 111.9f, 342.76f, 211.55f)
            fill.shader = null; fill.color = CREAM; cv.drawRoundRect(rect, 20.91f, 20.91f, fill)
            line.color = Look.COCOA; line.strokeWidth = w * 1.2f; cv.drawRoundRect(rect, 20.91f, 20.91f, line)
            cv.restore()
            flat(cv, plasterPad, BUTTER)
            cv.restore()
        }
    }

    /** the everyday face of each stage, as the designer drew it; it blinks now and then, and can yawn */
    private fun calm(cv: Canvas, stage: GrowthStage, w: Float, now: Long) {
        val yawning = yawn > 0.01f
        val shut = yawning || (now % 4300L) < 150L
        fun eye(x: Float, y: Float) { if (shut) { line.color = Look.COCOA; line.strokeWidth = w; cv.save(); cv.rotate(5.4f, x, y); cv.drawArc(oval(x, y - 3f, 9.5f, 6f), 20f, 140f, false, line); cv.restore() } else dot(cv, x, y, 10f) }
        fun gape(x: Float, y: Float) {
            val m = oval(x, y + 6f * yawn, 6f + 7f * yawn, 4f + 15f * yawn)
            fill.shader = null; fill.color = 0xFF8F3F49.toInt(); cv.drawOval(m, fill); line.color = Look.COCOA; line.strokeWidth = w * 0.9f; cv.drawOval(m, line)
            if (yawn > 0.4f) { fill.color = 0xFFE8838A.toInt(); cv.drawOval(oval(x, y + 6f * yawn + 9f * yawn, 4f + 4f * yawn, 4.5f * yawn), fill) }
        }
        when (stage) {
            GrowthStage.ADULT -> { eye(197.26f, 187.4f); eye(313.77f, 198.4f); if (yawning) gape(266f, 231f) else stroke(cv, aSmile, w) }
            GrowthStage.REBEL -> { if (shut) eye(306.8f, 217.6f) else flat(cv, rEye, Look.COCOA); if (yawning) gape(244f, 249f) else stroke(cv, rPout, w) }
            GrowthStage.INFANT -> { eye(196.19f, 214.77f); eye(312.71f, 225.77f) }
            GrowthStage.EGG -> { eye(214.03f, 204.69f); eye(307.51f, 216.24f); if (yawning) gape(264f, 228f) else stroke(cv, eSmile, w) }
        }
    }

    /** the baby's dummy: a sky-blue guard, a cream ring and its blue knob */
    private fun dummy(cv: Canvas, w: Float) {
        flat(cv, iDummy, SKY); stroke(cv, iDummy, w)
        cv.save(); cv.rotate(-67f, 235.58f, 255.48f)
        fill.shader = null; fill.color = CREAM; cv.drawOval(oval(235.58f, 255.48f, 15.68f, 18.97f), fill); line.color = Look.COCOA; line.strokeWidth = w; cv.drawOval(rect, line)
        fill.color = SKY; cv.drawOval(oval(235.58f, 255.48f, 8.89f, 10.76f), fill); cv.drawOval(rect, line)
        cv.restore()
    }

    // ------------------------------------------------------------------ clothes (hats and glasses in the head box, see the class comment)

    private val crown = Path().apply {
        moveTo(-0.42f, -1.93f * K); lineTo(-0.48f, -2.44f * K); lineTo(-0.22f, -2.2f * K); lineTo(0f, -2.54f * K); lineTo(0.22f, -2.2f * K)
        lineTo(0.48f, -2.44f * K); lineTo(0.42f, -1.93f * K); quadTo(0f, -1.86f * K, -0.42f, -1.93f * K); close()
    }
    private val strawDome = Path().apply { moveTo(-0.42f, -2.02f * K); cubicTo(-0.42f, -2.6f * K, 0.42f, -2.6f * K, 0.42f, -2.02f * K); close() }
    private val crownShader = LinearGradient(0f, -2.54f * K, 0f, -1.9f * K, intArrayOf(0xFFFFE88E.toInt(), 0xFFF9CB45.toInt(), 0xFFE9A92C.toInt()), floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP)
    private val brimShader = LinearGradient(0f, -2.1f * K, 0f, -1.84f * K, 0xFFF6DC8E.toInt(), 0xFFDDB560.toInt(), Shader.TileMode.CLAMP)
    private val domeShader = LinearGradient(0f, -2.5f * K, 0f, -2.0f * K, 0xFFFBE6A6.toInt(), 0xFFEBC874.toInt(), Shader.TileMode.CLAMP)
    private val beretShader = RadialGradient(-0.2f, -2.15f * K, 0.7f, 0xFFF59184.toInt(), 0xFFD9574A.toInt(), Shader.TileMode.CLAMP)
    private val lensShader = LinearGradient(0f, -1.3f * K - 0.14f * K, 0f, -1.3f * K + 0.18f * K, 0xFF4A4458.toInt(), 0xFF221E2A.toInt(), Shader.TileMode.CLAMP)
    private fun fillPath(cv: Canvas, path: Path, s: Shader) { fill.color = OPAQUE; fill.shader = s; cv.drawPath(path, fill); fill.shader = null }

    /** trousers on their own, about 2 wide and centred on the origin (the shop picture) */
    fun pantsIcon(cv: Canvas, id: String, ow: Float) {
        val jeans = id == "jeans"; val h = hem(id); val w = ow * 41f
        val shape = pantsOutline(id)
        cv.save(); cv.scale(1f / 41f, 1f / 41f); cv.translate(-256f, -(324f + h) / 2f)
        flat(cv, shape, if (jeans) JEANS else SHORTS)
        cv.save(); cv.clipPath(shape)
        if (jeans) { fill.color = JEANS_CUFF; cv.drawRect(200f, h - 6.5f, 312f, h, fill); line.color = Look.COCOA; line.strokeWidth = w * 0.55f; cv.drawLine(200f, h - 6.5f, 312f, h - 6.5f, line) }
        stitch.color = if (jeans) 0xFFC9D7F7.toInt() else 0xFFE6F4E6.toInt(); stitch.strokeWidth = w * 0.4f
        stitch.pathEffect = DashPathEffect(floatArrayOf(w * 0.9f, w * 0.9f), 0f)
        cv.drawLine(256.4f, 341f, 256.4f, 356f, stitch)
        line.strokeCap = Paint.Cap.BUTT
        line.color = Look.COCOA; line.strokeWidth = 7f + w * 1.1f; cv.drawPath(beltLine, line)
        line.color = if (jeans) JEANS_BELT else SHORTS_BELT; line.strokeWidth = 7f; cv.drawPath(beltLine, line)
        line.strokeCap = Paint.Cap.ROUND
        dot(cv, 256f, 334f, 2.6f, if (jeans) 0xFFF6C760.toInt() else Look.WHITE)
        cv.restore()
        stroke(cv, shape, w)
        line.color = Look.COCOA; line.strokeWidth = w * 0.8f; cv.drawLine(256.58f, 358f, 256.58f, h, line)
        cv.restore()
    }

    /** glasses, in the head box; [eyeX] is half the distance between the eyes. Also used for the shop picture */
    fun glasses(cv: Canvas, id: String?, back: Boolean, ow: Float, eyeX: Float = 0.42f) {
        id ?: return
        val eyeY = -1.3f * K
        if (back) {
            line.color = 0xFF2B2733.toInt(); line.strokeWidth = 0.05f
            for (sg in SIDES) cv.drawLine(sg * 1.0f, eyeY, sg * 0.84f, eyeY, line)
            return
        }
        if (id == "sun") {
            val hw = 0.27f; val r1 = 0.08f; val r2 = 0.16f
            for (sg in SIDES) {
                tmp.reset(); tmp.addRoundRect(sg * eyeX - hw, eyeY - 0.15f * K, sg * eyeX + hw, eyeY + 0.2f * K, floatArrayOf(r1, r1, r1, r1, r2, r2, r2, r2), Path.Direction.CW)
                fill.color = OPAQUE; fill.shader = lensShader; cv.drawPath(tmp, fill); fill.shader = null
                line.color = Look.COCOA; line.strokeWidth = ow * 0.7f; cv.drawPath(tmp, line)
                cv.save(); cv.clipPath(tmp)
                tmp.reset(); tmp.moveTo(sg * eyeX - 0.2f, eyeY + 0.22f * K); tmp.lineTo(sg * eyeX - 0.06f, eyeY + 0.22f * K); tmp.lineTo(sg * eyeX + 0.12f, eyeY - 0.2f * K); tmp.lineTo(sg * eyeX - 0.02f, eyeY - 0.2f * K); tmp.close()
                flat(cv, tmp, 0x59FFFFFF); cv.restore()
            }
            line.color = 0xFF2B2733.toInt(); line.strokeWidth = 0.055f
            tmp.reset(); tmp.moveTo(-eyeX + hw, eyeY - 0.05f * K); tmp.quadTo(0f, eyeY - 0.12f * K, eyeX - hw, eyeY - 0.05f * K); cv.drawPath(tmp, line)
        } else {
            val r = 0.21f
            for (sg in SIDES) {
                fill.shader = null; fill.color = 0x59C8E1FF; cv.drawCircle(sg * eyeX, eyeY, r, fill)
                line.color = Look.COCOA; line.strokeWidth = 0.05f; cv.drawCircle(sg * eyeX, eyeY, r, line)
            }
            tmp.reset(); tmp.moveTo(-eyeX + r, eyeY); tmp.quadTo(0f, eyeY - 0.08f * K, eyeX - r, eyeY); cv.drawPath(tmp, line)
        }
    }

    /** hats, in the head box (the top of the head is at y = -2K); also used for the shop picture */
    fun hat(cv: Canvas, id: String?, ow: Float) {
        when (id) {
            "straw" -> {
                val r = oval(0f, -1.98f * K, 0.82f, 0.15f * K)
                fill.color = OPAQUE; fill.shader = brimShader; cv.drawOval(r, fill); fill.shader = null; line.color = Look.COCOA; line.strokeWidth = ow * 0.85f; cv.drawOval(r, line)
                fillPath(cv, strawDome, domeShader); stroke(cv, strawDome, ow * 0.85f)
                rect.set(-0.42f, -2.16f * K, 0.42f, -2.04f * K); fill.color = 0xFFF07F6E.toInt(); cv.drawRoundRect(rect, 0.03f, 0.03f, fill)
                line.strokeWidth = ow * 0.6f; cv.drawRoundRect(rect, 0.03f, 0.03f, line)
            }
            "beret" -> {
                cv.save(); cv.rotate(-4.6f)
                val r = oval(-0.04f, -2.08f * K, 0.66f, 0.25f * K)
                fill.color = OPAQUE; fill.shader = beretShader; cv.drawOval(r, fill); fill.shader = null; line.color = Look.COCOA; line.strokeWidth = ow * 0.85f; cv.drawOval(r, line)
                rect.set(-0.07f, -2.42f * K, 0.01f, -2.28f * K); fill.color = 0xFFB8483D.toInt(); cv.drawRoundRect(rect, 0.03f, 0.03f, fill)
                line.strokeWidth = ow * 0.6f; cv.drawRoundRect(rect, 0.03f, 0.03f, line)
                cv.restore()
            }
            "crown" -> {
                fillPath(cv, crown, crownShader); stroke(cv, crown, ow * 0.85f)
                for (b in CROWN_TIPS) { fill.color = 0xFFFFF2B8.toInt(); cv.drawCircle(b[0], b[1] * K, 0.05f, fill); line.strokeWidth = ow * 0.5f; cv.drawCircle(b[0], b[1] * K, 0.05f, line) }
                fill.color = 0xFFF2706B.toInt(); cv.drawCircle(0f, -2.1f * K, 0.065f, fill); cv.drawCircle(0f, -2.1f * K, 0.065f, line)
                for (sg in SIDES) { fill.color = 0xFF6F9BEA.toInt(); cv.drawCircle(sg * 0.25f, -2.06f * K, 0.045f, fill); line.strokeWidth = ow * 0.45f; cv.drawCircle(sg * 0.25f, -2.06f * K, 0.045f, line) }
                fill.color = 0xBFFFFFFF.toInt(); cv.drawCircle(-0.02f, -2.13f * K, 0.02f, fill)
            }
        }
    }

    /** only the outline of the hat, for the white rim of the small pet ([line] is already set up) */
    private fun hatSilhouette(cv: Canvas, id: String) {
        when (id) {
            "straw" -> { cv.drawOval(oval(0f, -1.98f * K, 0.82f, 0.15f * K), line); cv.drawPath(strawDome, line) }
            "beret" -> { cv.save(); cv.rotate(-4.6f); cv.drawOval(oval(-0.04f, -2.08f * K, 0.66f, 0.25f * K), line); cv.restore() }
            "crown" -> cv.drawPath(crown, line)
        }
    }

    /** only the grown bear's head, about 2 wide and centred on the origin (the menu portrait and the small icons) */
    fun head(cv: Canvas, st: PetState, ow: Float, now: Long = 1000L) {
        val t = tone(st); val w = ow * PX
        pass = 2
        cv.save(); cv.scale(1f / PX, 1f / PX); cv.translate(-256f, -200f)
        solid(cv, aHead, t.body, w)
        flat(cv, aEarL, t.earIn); flat(cv, aEarR, t.earIn)
        face(cv, st, GrowthStage.ADULT, w, now)
        cv.restore()
    }

    /** debug: every stage in every mood, front and back, bare and dressed, on one sheet saved as a PNG */
    fun sheet(file: java.io.File, cell: Int = 300) {
        val outfits = listOf(emptyMap<Slot, String>(), mapOf(Slot.HAT to "straw", Slot.GLASSES to "round", Slot.PANTS to "shorts"),
            mapOf(Slot.HAT to "beret", Slot.GLASSES to "sun", Slot.PANTS to "jeans"), mapOf(Slot.HAT to "crown", Slot.PANTS to "jeans"))
        val stages = GrowthStage.values(); val states = PetState.values()
        val cols = states.size + outfits.size - 1 + 3 + 2
        val bmp = Bitmap.createBitmap(cell * cols, cell * stages.size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(0xFFEFE6D8.toInt())
        val u = cell * 0.3f
        for ((r, stage) in stages.withIndex()) {
            var col = 0
            fun cellDraw(st: PetState, outfit: Map<Slot, String>, back: Boolean, walking: Boolean, stepT: Float, sticker: Boolean) {
                c.save(); c.translate(cell * (col + 0.5f), cell * (r + 0.95f)); c.scale(u, u)
                draw(c, st, stage, outfit, back, walking, stepT, 0.047f, 1000L, sticker)
                c.restore(); col++
            }
            for (st in states) cellDraw(st, emptyMap(), false, false, 0f, false)
            for (o in outfits.drop(1)) cellDraw(PetState.NORMAL, o, false, false, 0f, false)
            cellDraw(PetState.NORMAL, outfits[1], true, false, 0f, false)
            cellDraw(PetState.NORMAL, outfits[2], true, true, 1.57f, true)
            cellDraw(PetState.HURT, outfits[3], false, false, 0f, true)
            cellDraw(PetState.NORMAL, outfits[2], false, true, 1.57f, false)
            cellDraw(PetState.NORMAL, outfits[1], false, true, 4.71f, false)
        }
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val SIDES = floatArrayOf(-1f, 1f)
    private val CROWN_TIPS = arrayOf(floatArrayOf(-0.48f, -2.44f), floatArrayOf(0f, -2.54f), floatArrayOf(0.48f, -2.44f))
    private val JEANS = 0xFF6F90E0.toInt()
    private val JEANS_CUFF = 0xFFBFD0F6.toInt()
    private val JEANS_BELT = 0xFF3F5BB0.toInt()
    private val SHORTS = 0xFF8FCB98.toInt()
    private val SHORTS_BELT = 0xFF4E9560.toInt()
}
