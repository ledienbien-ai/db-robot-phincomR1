package info.dourok.voicebot.data.model


import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertNotNull
import junit.framework.TestCase.assertTrue
import org.json.JSONException
import org.json.JSONObject
import org.junit.Assert.assertThrows
import org.junit.Test

class DeviceInfoKtTest {

    @Test
    fun `toJson array`() {
        val deviceInfo = DummyDataGenerator.generate()
        val json = deviceInfo.toJson()
        val obj = JSONObject(json)
        val features = obj.getJSONObject("board").getJSONArray("features")
        val partition = obj.getJSONArray("partition_table")
        assertEquals(4, features.length())
        assertEquals(3, partition.length())
    }


    @Test
    fun `toJson basic functionality`() {
        val deviceInfo = DummyDataGenerator.generate()
        val json = deviceInfo.toJson()
        assertNotNull(json)
        assertTrue(json.contains("\"version\":"))
    }

    @Test
    fun `fromJsonToDeviceInfo basic functionality`() {
        val json = DummyDataGenerator.generate().toJson()
        val deviceInfo = fromJsonToDeviceInfo(json)
        assertNotNull(deviceInfo)
        assertEquals(2, deviceInfo.version)
    }

    @Test
    fun `toJson and fromJsonToDeviceInfo round trip`() {
        val originalDeviceInfo = DummyDataGenerator.generate()
        val json = originalDeviceInfo.toJson()
        val newDeviceInfo = fromJsonToDeviceInfo(json)
        assertEquals(originalDeviceInfo, newDeviceInfo)
    }

    @Test
    fun `fromJsonToDeviceInfo missing version`() {
        val json = """{"flash_size":8388608}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo missing chip info`() {
        val json = """{"version":2,"flash_size":8388608}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo missing application`() {
        val json = """{"version":2,"flash_size":8388608,"chip_info":{"model":3,"cores":2,"revision":1,"features":5}}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo missing partition table`() {
        val json = """{"version":2,"flash_size":8388608,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"}}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo missing ota`() {
        val json = """{"version":2,"flash_size":8388608,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}]}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo missing board`() {
        val json = """{"version":2,"flash_size":8388608,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":"ota_1"}}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo invalid JSON`() {
        val json = """{"version":2,"flash_size":8388608"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo empty JSON`() {
        val json = """{}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo wrong type`() {
        val json = """{"version":"two","flash_size":8388608}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `toJson empty board features`() {
        val deviceInfo = DummyDataGenerator.generate().copy(board = Board("ESP32S3-DevKitM-1", "v1.2", emptyList(), "Espressif", "ESP32S3-1234"))
        val json = deviceInfo.toJson()
        assertTrue(json.contains("\"features\":[]"))
    }



    @Test
    fun `toJson empty partition table`() {
        val deviceInfo = DummyDataGenerator.generate().copy(partition_table = emptyList())
        val json = deviceInfo.toJson()
        assertTrue(json.contains("\"partition_table\":[]"))
    }



    @Test
    fun `fromJsonToDeviceInfo invalid chip info`() {
        val json = """{"version":2,"flash_size":8388608,"chip_info":{"model":"three","cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":"ota_1"},"board":{"name":"ESP32S3-DevKitM-1","revision":"v1.2","features":["WiFi","Bluetooth"],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo invalid application`() {
        val json = """{"version":2,"flash_size":8388608,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":123,"version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":"ota_1"},"board":{"name":"ESP32S3-DevKitM-1","revision":"v1.2","features":["WiFi","Bluetooth"],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo invalid ota`() {
        val json = """{"version":2,"flash_size":8388608,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":123},"board":{"name":"ESP32S3-DevKitM-1","revision":"v1.2","features":["WiFi","Bluetooth"],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        assertThrows(JSONException::class.java){
            fromJsonToDeviceInfo(json)
        }
    }

    @Test
    fun `fromJsonToDeviceInfo invalid board`() {
        val json = """{"version":2,"flash_size":8388608,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":"ota_1"},"board":{"name":123,"revision":"v1.2","features":["WiFi","Bluetooth"],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        assertThrows(JSONException::class.java) {
            fromJsonToDeviceInfo(json)
        }
    }



    @Test
    fun `toJson int min`() {
        val deviceInfo = DummyDataGenerator.generate().copy(version = Int.MIN_VALUE)
        val json = deviceInfo.toJson()
        assertTrue(json.contains("\"version\":${Int.MIN_VALUE}"))
    }

    @Test
    fun `toJson int max`() {
        val deviceInfo = DummyDataGenerator.generate().copy(version = Int.MAX_VALUE)
        val json = deviceInfo.toJson()
        assertTrue(json.contains("\"version\":${Int.MAX_VALUE}"))
    }



    @Test
    fun `toJson string max length`() {
        val longString = "a".repeat(10000)
        val deviceInfo = DummyDataGenerator.generate().copy(mac_address = longString)
        val json = deviceInfo.toJson()
        assertTrue(json.contains("\"mac_address\":\"$longString\""))
    }


    @Test
    fun `fromJsonToDeviceInfo empty partition table`() {
        val json = """{"version":2,"flash_size":8388608,"psram_size":4194304,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[],"ota":{"label":"ota_1"},"board":{"name":"ESP32S3-DevKitM-1","revision":"v1.2","features":["WiFi","Bluetooth"],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        val deviceInfo = fromJsonToDeviceInfo(json)
        assertTrue(deviceInfo.partition_table.isEmpty())
    }

    @Test
    fun `fromJsonToDeviceInfo int min`() {
        val json = """{"version":${Int.MIN_VALUE},"flash_size":8388608,"psram_size":4194304,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":"ota_1"},"board":{"name":"ESP32S3-DevKitM-1","revision":"v1.2","features":["WiFi","Bluetooth"],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        val deviceInfo = fromJsonToDeviceInfo(json)
        assertEquals(Int.MIN_VALUE, deviceInfo.version)
    }

    @Test
    fun `fromJsonToDeviceInfo int max`() {
        val json = """{"version":${Int.MAX_VALUE},"flash_size":8388608,"psram_size":4194304,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":"ota_1"},"board":{"name":"ESP32S3-DevKitM-1","revision":"v1.2","features":["WiFi","Bluetooth"],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        val deviceInfo = fromJsonToDeviceInfo(json)
        assertEquals(Int.MAX_VALUE, deviceInfo.version)
    }

    @Test
    fun `fromJsonToDeviceInfo extra json key`() {
        val json = """{"version":2,"flash_size":8388608,"psram_size":4194304,"extra_key":"extra_value","chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":"ota_1"},"board":{"name":"ESP32S3-DevKitM-1","revision":"v1.2","features":["WiFi","Bluetooth"],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        val deviceInfo = fromJsonToDeviceInfo(json)
        assertNotNull(deviceInfo)
    }

    @Test
    fun `fromJsonToDeviceInfo empty board features`() {
        val json = """{"version":2,"flash_size":8388608,"psram_size":4194304,"chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":"ota_1"},"board":{"name":"ESP32S3-DevKitM-1","revision":"v1.2","features":[],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        val deviceInfo = fromJsonToDeviceInfo(json)
        assertTrue(deviceInfo.board.features.isEmpty())
    }

    @Test
    fun `fromJsonToDeviceInfo string max length`() {
        val longString = "a".repeat(10000)
        val json = """{"version":2,"flash_size":8388608,"psram_size":4194304,"mac_address":"$longString","chip_info":{"model":3,"cores":2,"revision":1,"features":5},"application":{"name":"sensor-hub","version":"1.3.0","compile_time":"2025-02-28T12:34:56Z","idf_version":"5.1-beta","elf_sha256":"dummy_sha256"},"partition_table":[{"label":"app","type":1,"subtype":2,"address":65536,"size":2097152}],"ota":{"label":"ota_1"},"board":{"name":"ESP32S3-DevKitM-1","revision":"v1.2","features":["WiFi","Bluetooth"],"manufacturer":"Espressif","serial_number":"ESP32S3-1234"}}"""
        val deviceInfo = fromJsonToDeviceInfo(json)
        assertEquals(longString, deviceInfo.mac_address)
    }
}