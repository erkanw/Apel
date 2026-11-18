package io.github.erkanw.vault

import io.github.erkanw.vault.chat.currentSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationParseTest {
    private val body = """{"jobs":[
        {"id":"j1","name":"Morning brief (weekdays)","enabled":true,"protected":false,
         "schedule":{"kind":"cron","expr":"0 7 * * 1-5","tz":"Europe/Stockholm"},
         "payload":{"message":"Give me my brief.\nInclude the weather & \"my\" calendar <today>."},
         "origin":{"channel":"telegram"},"state":{}},
        {"id":"j2","name":"Water","enabled":true,"schedule":{"kind":"every","every_ms":7200000},"payload":{"message":"Drink"},"state":{}},
        {"id":"j3","name":"Once","enabled":true,"schedule":{"kind":"at","at_ms":1760000000000},"payload":{"message":"Do"},"state":{}}]}"""

}
