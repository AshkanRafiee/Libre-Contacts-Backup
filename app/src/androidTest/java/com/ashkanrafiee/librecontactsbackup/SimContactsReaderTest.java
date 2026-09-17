package com.ashkanrafiee.librecontactsbackup;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.ashkanrafiee.librecontactsbackup.snapshot.SimContact;
import com.ashkanrafiee.librecontactsbackup.snapshot.SimContactsReader;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for {@link SimContactsReader}. The test device may or may not expose a
 * SIM phonebook (real SIM, emulator virtual SIM, or none at all), so the
 * crucial contract is that reading is always best-effort: it must never throw,
 * and any entries it does return must be shaped correctly.
 */
@RunWith(AndroidJUnit4.class)
public class SimContactsReaderTest {

    private ContentResolver resolver;

    @Before
    public void setUp() {
        android.app.UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(android.Manifest.permission.READ_CONTACTS, android.Manifest.permission.WRITE_CONTACTS);
        resolver = InstrumentationRegistry.getInstrumentation().getTargetContext().getContentResolver();
    }

    @Test public void read_never_throws_even_without_a_sim() {
        SimContactsReader.Result result = SimContactsReader.readSimContacts(resolver);
        assertNotNull(result);
        assertNotNull(result.contacts);
    }

    @Test public void returned_entries_are_well_formed() {
        SimContactsReader.Result result = SimContactsReader.readSimContacts(resolver);
        for (SimContact sim : result.contacts) {
            assertFalse("a read SIM entry must carry at least a name or a number", sim.isEmpty());
            assertNotNull(sim.number);
        }
    }

    @Test public void reading_twice_yields_identical_entries() {
        SimContactsReader.Result first = SimContactsReader.readSimContacts(resolver);
        SimContactsReader.Result second = SimContactsReader.readSimContacts(resolver);
        assertTrue(first.contacts.size() == second.contacts.size());
        for (int i = 0; i < first.contacts.size(); i++) {
            SimContact a = first.contacts.get(i);
            SimContact b = second.contacts.get(i);
            assertTrue((a.name == null ? b.name == null : a.name.equals(b.name))
                    && a.number.equals(b.number));
        }
    }
}