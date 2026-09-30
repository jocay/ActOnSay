package com.voice.actonsay

import com.voice.actonsay.asr.KeywordDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream

class PinyinHelperTest {

    @Test
    fun testPinyinDictMapping() {
        val dictFile = File("src/main/assets/pinyin_dict.txt")
        assertTrue("pinyin_dict.txt should exist", dictFile.exists())

        val map = mutableMapOf<Char, String>()
        dictFile.forEachLine { line ->
            val idx = line.indexOf(':')
            if (idx > 0) {
                map[line[0]] = line.substring(idx + 1).trim()
            }
        }

        assertEquals("n ǐ", map['你'])
        assertEquals("h ǎo", map['好'])
        assertEquals("x iǎo", map['小'])
        assertEquals("zh ì", map['智'])
        assertEquals("ài", map['爱'])
        assertEquals("t óng", map['同'])
        assertEquals("x ué", map['学'])
    }

    @Test
    fun testKeywordDetector() {
        val detector = KeywordDetector()
        detector.addKeyword("关灯")
        detector.addKeyword("打开空调")

        detector.check("请帮我关灯一下谢谢")
        assertEquals(1, detector.events.value.size)
        assertEquals("关灯", detector.events.value[0].keyword)

        detector.check("今天天气真好")
        assertEquals(1, detector.events.value.size)

        detector.check("麻烦打开空调吧")
        assertEquals(2, detector.events.value.size)
        assertEquals("打开空调", detector.events.value[1].keyword)
    }
}
