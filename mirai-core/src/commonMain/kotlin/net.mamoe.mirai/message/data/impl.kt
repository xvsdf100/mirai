/*
 * Copyright 2020 Mamoe Technologies and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license that can be found through the following link.
 *
 * https://github.com/mamoe/mirai/blob/master/LICENSE
 */

@file:Suppress("EXPERIMENTAL_API_USAGE")
@file:JvmMultifileClass
@file:JvmName("MessageUtils")

package net.mamoe.mirai.message.data

import net.mamoe.mirai.utils.MiraiExperimentalAPI
import net.mamoe.mirai.utils.MiraiInternalAPI
import kotlin.jvm.JvmMultifileClass
import kotlin.jvm.JvmName
import kotlin.jvm.JvmSynthetic
import kotlin.native.concurrent.SharedImmutable

/////////////////////////
//// IMPLEMENTATIONS ////
/////////////////////////


@OptIn(MiraiInternalAPI::class)
private fun Message.hasDuplicationOfConstrain(key: Message.Key<*>): Boolean {
    return when (this) {
        is SingleMessage -> (this as? ConstrainSingle<*>)?.key == key
        is CombinedMessage -> return this.left.hasDuplicationOfConstrain(key) || this.tail.hasDuplicationOfConstrain(key)
        is SingleMessageChainImpl -> (this.delegate as? ConstrainSingle<*>)?.key == key
        is MessageChainImplByCollection -> this.delegate.any { (it as? ConstrainSingle<*>)?.key == key }
        is MessageChainImplBySequence -> this.any { (it as? ConstrainSingle<*>)?.key == key }
        else -> error("stub")
    }
}

@OptIn(MiraiInternalAPI::class)
@JvmSynthetic
@Suppress("DEPRECATION_ERROR")
internal fun Message.followedByInternalForBinaryCompatibility(tail: Message): CombinedMessage {
    return CombinedMessage(EmptyMessageChain, this.followedBy(tail))
}

@JvmSynthetic
internal fun Message.contentEqualsImpl(another: Message, ignoreCase: Boolean): Boolean {
    if (!this.contentToString().equals(another.contentToString(), ignoreCase = ignoreCase)) return false
    return when {
        this is SingleMessage && another is SingleMessage -> true
        this is SingleMessage && another is MessageChain -> another.all { it is MessageMetadata || it is PlainText }
        this is MessageChain && another is SingleMessage -> this.all { it is MessageMetadata || it is PlainText }
        this is MessageChain && another is MessageChain -> {
            val anotherIterator = another.iterator()

            /**
             * 逐个判断非 [PlainText] 的 [Message] 是否 [equals]
             */
            this.forEachContent { thisElement ->
                if (thisElement.isPlain()) return@forEachContent
                for (it in anotherIterator) {
                    if (it.isPlain() || it !is MessageContent) continue
                    if (thisElement != it) return false
                }
            }
            return true
        }
        else -> error("shouldn't be reached")
    }
}

@JvmSynthetic
internal fun Message.followedByImpl(tail: Message): MessageChain {
    when {
        this is SingleMessage && tail is SingleMessage -> {
            if (this is ConstrainSingle<*> && tail is ConstrainSingle<*>) {
                if (this.key == tail.key)
                    return SingleMessageChainImpl(tail)
            }
            return CombinedMessage(this, tail)
        }

        this is SingleMessage -> { // tail is not
            tail as MessageChain

            if (this is ConstrainSingle<*>) {
                val key = this.key
                if (tail.any { (it as? ConstrainSingle<*>)?.key == key }) {
                    return tail
                }
            }
            return CombinedMessage(this, tail)
        }

        tail is SingleMessage -> {
            this as MessageChain

            if (tail is ConstrainSingle<*> && this.hasDuplicationOfConstrain(tail.key)) {
                val iterator = this.iterator()
                var tailUsed = false
                return MessageChainImplByCollection(
                    constrainSingleMessagesImpl {
                        if (iterator.hasNext()) {
                            iterator.next()
                        } else if (!tailUsed) {
                            tailUsed = true
                            tail
                        } else null
                    }
                )
            }

            return CombinedMessage(this, tail)
        }

        else -> { // both chain
            this as MessageChain
            tail as MessageChain

            var iterator = this.iterator()
            var tailUsed = false
            return MessageChainImplByCollection(
                constrainSingleMessagesImpl {
                    if (iterator.hasNext()) {
                        iterator.next()
                    } else if (!tailUsed) {
                        tailUsed = true
                        iterator = tail.iterator()
                        iterator.next()
                    } else null
                }
            )
        }
    }
}


@OptIn(MiraiExperimentalAPI::class)
@JvmSynthetic
internal fun Sequence<SingleMessage>.constrainSingleMessages(): List<SingleMessage> {
    val iterator = this.iterator()
    return constrainSingleMessagesImpl supplier@{
        if (iterator.hasNext()) {
            iterator.next()
        } else null
    }
}

@MiraiExperimentalAPI
@JvmSynthetic
internal inline fun constrainSingleMessagesImpl(iterator: () -> SingleMessage?): ArrayList<SingleMessage> {
    val list = ArrayList<SingleMessage>()
    var firstConstrainIndex = -1

    var next: SingleMessage?
    do {
        next = iterator()
        next?.let { singleMessage ->
            if (singleMessage is ConstrainSingle<*>) {
                if (firstConstrainIndex == -1) {
                    firstConstrainIndex = list.size // we are going to add one
                } else {
                    val key = singleMessage.key
                    val index = list.indexOfFirst(firstConstrainIndex) { it is ConstrainSingle<*> && it.key == key }
                    if (index != -1) {
                        list[index] = singleMessage
                        return@let
                    }
                }
            }

            list.add(singleMessage)
        } ?: return list
    } while (true)
}

@JvmSynthetic
@OptIn(MiraiExperimentalAPI::class)
internal fun Iterable<SingleMessage>.constrainSingleMessages(): List<SingleMessage> {
    val iterator = this.iterator()
    return constrainSingleMessagesImpl supplier@{
        if (iterator.hasNext()) {
            iterator.next()
        } else null
    }
}

@JvmSynthetic
internal inline fun <T> List<T>.indexOfFirst(offset: Int, predicate: (T) -> Boolean): Int {
    for (index in offset..this.lastIndex) {
        if (predicate(this[index]))
            return index
    }
    return -1
}


@OptIn(MiraiExperimentalAPI::class)
@JvmSynthetic
@Suppress("UNCHECKED_CAST", "DEPRECATION_ERROR")
internal fun <M : Message> MessageChain.firstOrNullImpl(key: Message.Key<M>): M? = when (key) {
    At -> firstIsInstanceOrNull<At>()
    AtAll -> firstIsInstanceOrNull<AtAll>()
    PlainText -> firstIsInstanceOrNull<PlainText>()
    Image -> firstIsInstanceOrNull<Image>()
    OnlineImage -> firstIsInstanceOrNull<OnlineImage>()
    OfflineImage -> firstIsInstanceOrNull<OfflineImage>()
    GroupImage -> firstIsInstanceOrNull<GroupImage>()
    FriendImage -> firstIsInstanceOrNull<FriendImage>()
    Face -> firstIsInstanceOrNull<Face>()
    QuoteReply -> firstIsInstanceOrNull<QuoteReply>()
    MessageSource -> firstIsInstanceOrNull<MessageSource>()
    OnlineMessageSource -> firstIsInstanceOrNull<OnlineMessageSource>()
    OfflineMessageSource -> firstIsInstanceOrNull<OfflineMessageSource>()
    OnlineMessageSource.Outgoing -> firstIsInstanceOrNull<OnlineMessageSource.Outgoing>()
    OnlineMessageSource.Outgoing.ToGroup -> firstIsInstanceOrNull<OnlineMessageSource.Outgoing.ToGroup>()
    OnlineMessageSource.Outgoing.ToFriend -> firstIsInstanceOrNull<OnlineMessageSource.Outgoing.ToFriend>()
    OnlineMessageSource.Incoming -> firstIsInstanceOrNull<OnlineMessageSource.Incoming>()
    OnlineMessageSource.Incoming.FromGroup -> firstIsInstanceOrNull<OnlineMessageSource.Incoming.FromGroup>()
    OnlineMessageSource.Incoming.FromFriend -> firstIsInstanceOrNull<OnlineMessageSource.Incoming.FromFriend>()
    OnlineMessageSource -> firstIsInstanceOrNull<OnlineMessageSource>()
    XmlMessage -> firstIsInstanceOrNull<XmlMessage>()
    LongMessage -> firstIsInstanceOrNull()
    JsonMessage -> firstIsInstanceOrNull<JsonMessage>()
    RichMessage -> firstIsInstanceOrNull<RichMessage>()
    LightApp -> firstIsInstanceOrNull<LightApp>()
    PokeMessage -> firstIsInstanceOrNull<PokeMessage>()
    HummerMessage -> firstIsInstanceOrNull<HummerMessage>()
    FlashImage -> firstIsInstanceOrNull<FlashImage>()
    GroupFlashImage -> firstIsInstanceOrNull<GroupFlashImage>()
    FriendFlashImage -> firstIsInstanceOrNull<FriendFlashImage>()
    CustomMessage -> firstIsInstanceOrNull()
    CustomMessageMetadata -> firstIsInstanceOrNull()
    ForwardMessage -> firstIsInstanceOrNull()
    else -> {
        this.forEach { message ->
            if (message is CustomMessage) {
                @Suppress("UNCHECKED_CAST")
                if (message.getFactory() == key) {
                    return message as? M
                        ?: error("cannot cast ${message::class.qualifiedName}. Make sure CustomMessage.getFactory returns a factory that has a generic type which is the same as the type of your CustomMessage")
                }
            }
        }

        null
    }
} as M?

/**
 * 使用 [Collection] 作为委托的 [MessageChain]
 */
internal class MessageChainImplByCollection constructor(
    internal val delegate: Collection<SingleMessage> // 必须 constrainSingleMessages, 且为 immutable
) : Message, Iterable<SingleMessage>, MessageChain {
    override val size: Int get() = delegate.size
    override fun iterator(): Iterator<SingleMessage> = delegate.iterator()

    private var toStringTemp: String? = null
        get() = field ?: this.delegate.joinToString("") { it.toString() }.also { field = it }

    override fun toString(): String = toStringTemp!!

    private var contentToStringTemp: String? = null
        get() = field ?: this.delegate.joinToString("") { it.contentToString() }.also { field = it }

    override fun contentToString(): String = contentToStringTemp!!
}

/**
 * 使用 [Iterable] 作为委托的 [MessageChain]
 */
internal class MessageChainImplBySequence constructor(
    delegate: Sequence<SingleMessage> // 可以有重复 ConstrainSingle
) : Message, Iterable<SingleMessage>, MessageChain {
    override val size: Int by lazy { collected.size }

    /**
     * [Sequence] 可能只能消耗一遍, 因此需要先转为 [List]
     */
    private val collected: List<SingleMessage> by lazy { delegate.constrainSingleMessages() }
    override fun iterator(): Iterator<SingleMessage> = collected.iterator()

    private var toStringTemp: String? = null
        get() = field ?: this.joinToString("") { it.toString() }.also { field = it }

    override fun toString(): String = toStringTemp!!

    private var contentToStringTemp: String? = null
        get() = field ?: this.joinToString("") { it.contentToString() }.also { field = it }

    override fun contentToString(): String = contentToStringTemp!!
}

/**
 * 单个 [SingleMessage] 作为 [MessageChain]
 */
internal class SingleMessageChainImpl constructor(
    internal val delegate: SingleMessage
) : Message, Iterable<SingleMessage>, MessageChain {
    override val size: Int get() = 1
    override fun toString(): String = this.delegate.toString()
    override fun contentToString(): String = this.delegate.contentToString()
    override fun iterator(): Iterator<SingleMessage> = iterator { yield(delegate) }
}


//////////////////////
// region Image impl
//////////////////////


@SharedImmutable
@get:JvmSynthetic
internal val EMPTY_BYTE_ARRAY = ByteArray(0)


@JvmSynthetic
@Suppress("NOTHING_TO_INLINE") // no stack waste
internal inline fun Char.hexDigitToByte(): Int {
    return when (this) {
        in '0'..'9' -> this - '0'
        in 'A'..'F' -> 10 + (this - 'A')
        in 'a'..'f' -> 10 + (this - 'a')
        else -> throw IllegalArgumentException("illegal hex digit: $this")
    }
}

@JvmSynthetic
internal fun String.skipToSecondHyphen(): Int {
    var count = 0
    this.forEachIndexed { index, c ->
        if (c == '-' && ++count == 2) return index
    }
    error("cannot find two hyphens")
}

@JvmSynthetic
internal fun String.imageIdToMd5(offset: Int): ByteArray {
    val result = ByteArray(16)
    var cur = 0
    var hasCurrent = false
    var lastChar: Char = 0.toChar()
    for (index in offset..this.lastIndex) {
        val char = this[index]
        if (char == '-') continue
        if (hasCurrent) {
            result[cur++] = (lastChar.hexDigitToByte().shl(4) or char.hexDigitToByte()).toByte()
            if (cur == 16) return result
            hasCurrent = false
        } else {
            lastChar = char
            hasCurrent = true
        }
    }
    error("No enough chars")
}

@OptIn(ExperimentalStdlibApi::class)
internal fun calculateImageMd5ByImageId(imageId: String): ByteArray {
    @Suppress("DEPRECATION")
    return when {
        imageId matches FRIEND_IMAGE_ID_REGEX_2 -> imageId.imageIdToMd5(imageId.skipToSecondHyphen() + 1)
        imageId matches FRIEND_IMAGE_ID_REGEX_1 -> imageId.imageIdToMd5(1)
        imageId matches GROUP_IMAGE_ID_REGEX -> imageId.imageIdToMd5(1)
        imageId matches GROUP_IMAGE_ID_REGEX_OLD -> imageId.imageIdToMd5(1)

        else -> error(
            "illegal imageId: $imageId. $ILLEGAL_IMAGE_ID_EXCEPTION_MESSAGE"
        )
    }
}

internal val ILLEGAL_IMAGE_ID_EXCEPTION_MESSAGE =
    "ImageId must matches Regex `${FRIEND_IMAGE_ID_REGEX_1.pattern}`, " +
            "`${FRIEND_IMAGE_ID_REGEX_2.pattern}` or " +
            "`${GROUP_IMAGE_ID_REGEX.pattern}`"

// endregion