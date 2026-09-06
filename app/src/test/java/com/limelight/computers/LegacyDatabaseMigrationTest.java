package com.limelight.computers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mockStatic;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;

import androidx.test.core.app.ApplicationProvider;

import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.NvHTTP;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {35}, shadows = {
        com.limelight.shadows.ShadowMoonBridge.class,
        com.limelight.shadows.ShadowGameManager.class,
})
public final class LegacyDatabaseMigrationTest {
    private Context context;

    @Before
    public void setUp() {
        context = ApplicationProvider.getApplicationContext();
        for (String name : new String[]{
                "computers.db", "computers2.db", "computers3.db", "computers4.db"}) {
            context.deleteDatabase(name);
        }
    }

    @Test
    public void absentLegacyFilesDoNotCallSqlite() {
        try (MockedStatic<SQLiteDatabase> sqlite = mockStatic(SQLiteDatabase.class)) {
            assertTrue(LegacyDatabaseReader.migrateAllComputers(context).isEmpty());
            assertTrue(LegacyDatabaseReader2.migrateAllComputers(context).isEmpty());
            assertTrue(LegacyDatabaseReader3.migrateAllComputers(context).isEmpty());

            // Opening a missing file logs an Android SQLite error before the reader can catch it.
            sqlite.verifyNoInteractions();
        }
    }

    @Test
    public void existingLegacySchemasMigrateAlongsideCurrentComputersAndAreRetired() {
        ComputerDetails current = new ComputerDetails();
        current.uuid = "current";
        current.name = "Current computer";
        ComputerDatabaseManager manager = new ComputerDatabaseManager(context);
        try {
            assertTrue(manager.updateComputer(current));
        } finally {
            manager.close();
        }

        try (SQLiteDatabase db = context.openOrCreateDatabase("computers.db", 0, null)) {
            db.execSQL("CREATE TABLE Computers (Name TEXT, UUID TEXT, LocalAddress BLOB, "
                    + "RemoteAddress BLOB, MacAddress TEXT)");
            db.execSQL("INSERT INTO Computers VALUES (?, ?, ?, ?, ?)", new Object[]{
                    "First schema", "legacy1", new byte[]{10, 0, 0, 1},
                    new byte[]{(byte) 192, 0, 2, 1}, "00:11:22:33:44:01"});
        }
        try (SQLiteDatabase db = context.openOrCreateDatabase("computers2.db", 0, null)) {
            // The older form of this schema does not have a certificate column.
            db.execSQL("CREATE TABLE Computers (UUID TEXT, Name TEXT, LocalAddress TEXT, "
                    + "RemoteAddress TEXT, ManualAddress TEXT, MacAddress TEXT)");
            db.execSQL("INSERT INTO Computers VALUES (?, ?, ?, ?, ?, ?)", new Object[]{
                    "legacy2", "Second schema", "local.example", "remote.example",
                    "manual.example", "00:11:22:33:44:02"});
        }
        try (SQLiteDatabase db = context.openOrCreateDatabase("computers3.db", 0, null)) {
            db.execSQL("CREATE TABLE Computers (UUID TEXT, Name TEXT, Addresses TEXT, "
                    + "MacAddress TEXT, ServerCert BLOB)");
            db.execSQL("INSERT INTO Computers VALUES (?, ?, ?, ?, ?)", new Object[]{
                    "legacy3", "Third schema", "10.0.0.3_47990;remote.example_48000;manual.example;",
                    "00:11:22:33:44:03", null});
        }

        manager = new ComputerDatabaseManager(context);
        try {
            Map<String, ComputerDetails> computers = byUuid(manager.getAllComputers());
            assertEquals(4, computers.size());
            assertEquals("Current computer", computers.get("current").name);

            ComputerDetails first = computers.get("legacy1");
            assertNotNull(first);
            assertEquals("First schema", first.name);
            assertAddress(first.localAddress, "10.0.0.1", NvHTTP.DEFAULT_HTTP_PORT);
            assertAddress(first.remoteAddress, "192.0.2.1", NvHTTP.DEFAULT_HTTP_PORT);
            assertAddress(first.manualAddress, "192.0.2.1", NvHTTP.DEFAULT_HTTP_PORT);
            assertEquals("00:11:22:33:44:01", first.macAddress);

            ComputerDetails second = computers.get("legacy2");
            assertNotNull(second);
            assertAddress(second.localAddress, "local.example", NvHTTP.DEFAULT_HTTP_PORT);
            assertAddress(second.remoteAddress, "remote.example", NvHTTP.DEFAULT_HTTP_PORT);
            assertAddress(second.manualAddress, "manual.example", NvHTTP.DEFAULT_HTTP_PORT);
            assertNull(second.serverCert);

            ComputerDetails third = computers.get("legacy3");
            assertNotNull(third);
            assertAddress(third.localAddress, "10.0.0.3", 47990);
            assertAddress(third.remoteAddress, "remote.example", 48000);
            assertAddress(third.manualAddress, "manual.example", NvHTTP.DEFAULT_HTTP_PORT);
            assertEquals(48000, third.externalPort);
            assertNull(third.ipv6Address);
            assertNull(third.serverCert);
        } finally {
            manager.close();
        }

        assertFalse(context.getDatabasePath("computers.db").exists());
        assertFalse(context.getDatabasePath("computers2.db").exists());
        assertFalse(context.getDatabasePath("computers3.db").exists());

        // Reopening after retirement keeps all migrated/current entries without old files.
        manager = new ComputerDatabaseManager(context);
        try {
            assertEquals(4, manager.getAllComputers().size());
        } finally {
            manager.close();
        }
    }

    private static Map<String, ComputerDetails> byUuid(List<ComputerDetails> computers) {
        Map<String, ComputerDetails> byUuid = new HashMap<>();
        for (ComputerDetails computer : computers) {
            byUuid.put(computer.uuid, computer);
        }
        return byUuid;
    }

    private static void assertAddress(ComputerDetails.AddressTuple address, String host, int port) {
        assertNotNull(address);
        assertEquals(host, address.address);
        assertEquals(port, address.port);
    }
}
