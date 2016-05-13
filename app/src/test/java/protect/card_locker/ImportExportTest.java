package protect.card_locker;

import android.app.Activity;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.google.zxing.BarcodeFormat;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricGradleTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Calendar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricGradleTestRunner.class)
@Config(constants = BuildConfig.class, sdk = 17)
public class ImportExportTest
{
    private Activity activity;
    private DBHelper db;
    private long nowMs;
    private long lastYearMs;
    private final int MONTHS_PER_YEAR = 12;

    private final String BARCODE_DATA = "428311627547";
    private final String BARCODE_TYPE = BarcodeFormat.UPC_A.name();

    @Before
    public void setUp()
    {
        activity = Robolectric.setupActivity(MainActivity.class);
        db = new DBHelper(activity);
        nowMs = System.currentTimeMillis();

        Calendar lastYear = Calendar.getInstance();
        lastYear.set(Calendar.YEAR, lastYear.get(Calendar.YEAR)-1);
        lastYearMs = lastYear.getTimeInMillis();
    }

    /**
     * Add the given number of cards, each with
     * an index in the store name.
     * @param cardsToAdd
     */
    private void addLoyaltyCards(int cardsToAdd)
    {
        // Add in reverse order to test sorting
        for(int index = cardsToAdd; index > 0; index--)
        {
            String storeName = String.format("store, \"%4d", index);
            String note = String.format("note, \"%4d", index);
            boolean result = db.insertLoyaltyCard(storeName, note, BARCODE_DATA, BARCODE_TYPE);
            assertTrue(result);
        }

        assertEquals(cardsToAdd, db.getLoyaltyCardCount());
    }

    /**
     * Check that all of the cards follow the pattern
     * specified in addLoyaltyCards(), and are in sequential order
     * where the smallest card's index is 1
     */
    private void checkLoyaltyCards()
    {
        Cursor cursor = db.getLoyaltyCardCursor();
        int index = 1;

        while(cursor.moveToNext())
        {
            LoyaltyCard card = LoyaltyCard.toLoyaltyCard(cursor);

            String expectedStore = String.format("store, \"%4d", index);
            String expectedNote = String.format("note, \"%4d", index);

            assertEquals(expectedStore, card.store);
            assertEquals(expectedNote, card.note);
            assertEquals(BARCODE_DATA, card.cardId);
            assertEquals(BARCODE_TYPE, card.barcodeType);

            index++;
        }
        cursor.close();
    }

    /**
     * Delete the contents of the database
     */
    private void clearDatabase()
    {
        SQLiteDatabase database = db.getWritableDatabase();
        database.execSQL("delete from " + DBHelper.LoyaltyCardDbIds.TABLE);
        database.close();

        assertEquals(0, db.getLoyaltyCardCount());
    }

    @Test
    public void multipleCardsExportImport() throws IOException
    {
        final int NUM_CARDS = 1000;

        for(DataFormat format : DataFormat.values())
        {
            addLoyaltyCards(NUM_CARDS);

            ByteArrayOutputStream outData = new ByteArrayOutputStream();
            OutputStreamWriter outStream = new OutputStreamWriter(outData);

            // Export data to CSV format
            boolean result = MultiFormatExporter.exportData(db, outStream, format);
            assertTrue(result);
            outStream.close();

            clearDatabase();

            ByteArrayInputStream inData = new ByteArrayInputStream(outData.toByteArray());
            InputStreamReader inStream = new InputStreamReader(inData);

            // Import the CSV data
            result = MultiFormatImporter.importData(db, inStream, DataFormat.CSV);
            assertTrue(result);

            assertEquals(NUM_CARDS, db.getLoyaltyCardCount());

            checkLoyaltyCards();

            // Clear the database for the next format under test
            clearDatabase();
        }
    }

    @Test
    public void importExistingCardsNotReplace() throws IOException
    {
        final int NUM_CARDS = 1000;

        for(DataFormat format : DataFormat.values())
        {
            addLoyaltyCards(NUM_CARDS);

            ByteArrayOutputStream outData = new ByteArrayOutputStream();
            OutputStreamWriter outStream = new OutputStreamWriter(outData);

            // Export into CSV data
            boolean result = MultiFormatExporter.exportData(db, outStream, format);
            assertTrue(result);
            outStream.close();

            ByteArrayInputStream inData = new ByteArrayInputStream(outData.toByteArray());
            InputStreamReader inStream = new InputStreamReader(inData);

            // Import the CSV data on top of the existing database
            result = MultiFormatImporter.importData(db, inStream, DataFormat.CSV);
            assertTrue(result);

            assertEquals(NUM_CARDS, db.getLoyaltyCardCount());

            checkLoyaltyCards();

            // Clear the database for the next format under test
            clearDatabase();
        }
    }

    @Test
    public void corruptedImportNothingSaved() throws IOException
    {
        final int NUM_CARDS = 1000;

        for(DataFormat format : DataFormat.values())
        {
            addLoyaltyCards(NUM_CARDS);

            ByteArrayOutputStream outData = new ByteArrayOutputStream();
            OutputStreamWriter outStream = new OutputStreamWriter(outData);

            // Export data to CSV format
            boolean result = MultiFormatExporter.exportData(db, outStream, format);
            assertTrue(result);

            clearDatabase();

            String corruptEntry = "ThisStringIsLikelyNotPartOfAnyFormat";

            ByteArrayInputStream inData = new ByteArrayInputStream((outData.toString() + corruptEntry).getBytes());
            InputStreamReader inStream = new InputStreamReader(inData);

            // Attempt to import the CSV data
            result = MultiFormatImporter.importData(db, inStream, DataFormat.CSV);
            assertEquals(false, result);

            assertEquals(0, db.getLoyaltyCardCount());
        }
    }

    @Test
    public void useImportExportTask()
    {
        final int NUM_CARDS = 10;

        for(DataFormat format : DataFormat.values())
        {
            addLoyaltyCards(NUM_CARDS);

            // Export to whatever the default location is
            ImportExportTask task = new ImportExportTask(activity, false, format);
            task.execute();

            // Actually run the task to completion
            Robolectric.flushBackgroundThreadScheduler();

            clearDatabase();

            // Import everything back from the default location

            task = new ImportExportTask(activity, true, format);
            task.execute();

            // Actually run the task to completion
            Robolectric.flushBackgroundThreadScheduler();

            assertEquals(NUM_CARDS, db.getLoyaltyCardCount());

            checkLoyaltyCards();

            // Clear the database for the next format under test
            clearDatabase();
        }
    }
}
