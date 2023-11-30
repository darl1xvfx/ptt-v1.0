package ptt.utils

import ptt.client.CaptchaLocation
import ptt.client.UserSocket
import ptt.client.send
import ptt.commands.Command
import ptt.commands.CommandName
import mu.KotlinLogging
import org.koin.core.component.KoinComponent
import kotlin.random.Random.Default.nextBoolean
import kotlin.random.Random.Default.nextInt
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImageOp
import java.awt.image.ConvolveOp
import java.awt.image.Kernel
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.random.Random
import kotlin.random.Random.Default.nextDouble

class Captcha : KoinComponent {
    private val logger = KotlinLogging.logger { }

    private fun randColor(): Color {
        return Color(Random.nextInt(256), Random.nextInt(256), Random.nextInt(256))
    }


    var answer = ""
        private set
    var imageString: ByteArray? = null
        private set

    private val captcha = object {
        var width = 250
        var height = 38
        var path: String? = null
        var background: Color? = Color.BLACK
    }
    private val noise = object {
        var isAdded = false
        var fill = false
        var color: Color? = null
    }
    private val font = object {
        var size = 28
        var name = "Arial"
        var style = Font.BOLD
    }
    private val text = object {
        var length = 6
        var color: Color? = Color.BLACK
        var chars = ('a'..'z').toList() + ('0'..'9').toList()
    }
    private val line = object {
        var isAdded = false
        var vertical = 5
        var horizontal = 5
        var width = 1
        var color: Color? = text.color
    }

    private val border = object {
        var isAdded = false
        var blurRadius = 2
        var borderColor: Color? = Color.BLACK
    }

    fun build(): Captcha {
        fun randCaptcha() = (0 until text.length).map { text.chars.random() }.joinToString("")

        answer = randCaptcha()

        if (text.length <= 0) throw Exception("The text size must not be less than a character")
        if (captcha.width < font.size * text.length / 4.0) throw Exception("The captcha width should not be smaller than the text size")
        if (captcha.height < font.size) throw Exception("The captcha height should not be smaller than the text size")

        val positionX = captcha.width / 2.0 - (font.size * (text.length / 4.0))
        val positionY = captcha.height / 2.0 + (font.size / 2.0)

        val buffer = BufferedImage(captcha.width, captcha.height, BufferedImage.TYPE_INT_ARGB)
        val graphic = buffer.createGraphics()

        graphic.color = captcha.background
        graphic.fillRect(0, 0, captcha.width, captcha.height)

        if (noise.isAdded) {
            for (y in 0 until captcha.height) {
                for (x in 0 until captcha.width) {
                    val color = noise.color ?: randColor()
                    val noiseColor = Color(
                        color.red,
                        color.green,
                        color.blue,
                        Random.nextInt(70, 250)
                    )
                    if (noise.fill) buffer.setRGB(x, y, noiseColor.rgb)
                }
            }
        }

        graphic.font = Font(font.name, font.style, font.size)
        answer.forEachIndexed { i, c ->
            val x = positionX + (i * (font.size / 2))
            val y = positionY - font.size * 0.2 + Random.nextDouble(font.size * 0.2) * if (Random.nextBoolean()) -1 else 1
            graphic.color = Color.BLACK
            graphic.drawString(c.toString(), x.roundToInt(), y.roundToInt())
        }

        if (border.isAdded) {
            val kernelSize = border.blurRadius * 1
            val kernelData = FloatArray(kernelSize * kernelSize) { 1.0f / (kernelSize * kernelSize).toFloat() }
            val blurKernel = Kernel(kernelSize, kernelSize, kernelData)
            val blurOp: BufferedImageOp = ConvolveOp(blurKernel, ConvolveOp.EDGE_NO_OP, null)
            val blurredImage = blurOp.filter(buffer, null)

            graphic.color = border.borderColor
            graphic.drawImage(blurredImage, 0, 0, null)
        }

        val outputStream = ByteArrayOutputStream()
        ImageIO.write(buffer, "png", outputStream)
        val imageBytes = outputStream.toByteArray()

        imageString = imageBytes

        return this
    }

    suspend fun generateAndSendCaptcha(
        commandName: CommandName,
        captchaLocation: CaptchaLocation,
        socket: UserSocket
    ) {
        val captcha = Captcha().builder(280, 54, "./", Color(0, 0, 0, 0))
            .addLines(Random.nextInt(2, 5), 1, Random.nextInt(2, 4), Color.black)
            .addNoise(true, Color.decode("#716479"))
            .setFont("Myriad Pro", 50, Font.BOLD)
            .setText(5, Color.black)
            .addBorder(1 , Color.BLACK)
            .build()

        logger.info { "Generated captcha text: ${captcha.answer}" }
        captcha.imageString?.let { BlobUtils.encode(it) }?.let {
            Command(commandName, captchaLocation.key, it).send(socket)
        }

        socket.captcha[captchaLocation] = captcha.answer
    }

    fun builder(
        width: Int = captcha.width,
        height: Int = captcha.height,
        path: String? = captcha.path,
        background: Color? = captcha.background
    ): Captcha {
        captcha.width = width
        captcha.height = height
        captcha.path = path
        captcha.background = background
        return this
    }

    fun setFont(
        name: String = font.name,
        size: Int = font.size,
        style: Int = font.style
    ): Captcha {
        font.name = name
        font.size = size
        font.style = style
        return this
    }

    fun addLines(
        vertical: Int = line.vertical,
        horizontal: Int = line.horizontal,
        width: Int = line.width,
        color: Color? = line.color
    ): Captcha {
        line.isAdded = true
        line.vertical = vertical
        line.horizontal = horizontal
        line.width = width
        line.color = color
        return this
    }

    fun removeLines(): Captcha {
        line.isAdded = false
        return this
    }

    fun setText(length: Int = text.length, color: Color? = text.color) = setText(length, color, text.chars)

    fun setText(
        length: Int = text.length,
        color: Color? = text.color,
        chars: List<Char> = text.chars
    ): Captcha {
        text.length = length
        text.color = color
        text.chars = chars
        return this
    }

    fun addNoise(fill: Boolean = noise.fill, color: Color? = noise.color): Captcha {
        noise.isAdded = true
        noise.fill = fill
        noise.color = color
        return this
    }

    fun removeNoises(): Captcha {
        noise.isAdded = false
        return this
    }

    fun addBorder(blurRadius: Int = border.blurRadius, borderColor: Color? = border.borderColor): Captcha {
        border.isAdded = true
        border.blurRadius = blurRadius
        border.borderColor = borderColor
        return this
    }

    fun removeBorder(): Captcha {
        border.isAdded = false
        return this
    }
}