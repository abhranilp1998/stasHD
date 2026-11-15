package com.abhranil.stashd.data.auth

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsentDecisionTest {
    private val drive = listOf(DRIVE_READONLY_SCOPE)

    @Test fun grantedWhenOkWithScopeAndToken() =
        assertEquals(ConsentResult.Granted, ConsentDecision.decide(Activity.RESULT_OK, drive, "ya29.x", null))

    @Test fun backingOutIsCancelledNotDenied() =
        assertEquals(ConsentResult.Cancelled, ConsentDecision.decide(Activity.RESULT_CANCELED, null, null, null))

    @Test fun untickedDriveScopeIsDenied() {
        val r = ConsentDecision.decide(Activity.RESULT_OK, listOf("openid", "email"), "ya29.x", null)
        assertTrue(r is ConsentResult.Denied)
        assertEquals(ConsentDecision.DENIED_SCOPE, (r as ConsentResult.Denied).message)
    }

    @Test fun googleErrorIsDeniedWithMessage() {
        val r = ConsentDecision.decide(Activity.RESULT_CANCELED, null, null, "access_denied")
        assertEquals("access_denied", (r as ConsentResult.Denied).message)
    }

    @Test fun okWithoutTokenIsDenied() =
        assertTrue(ConsentDecision.decide(Activity.RESULT_OK, drive, null, null) is ConsentResult.Denied)
}
