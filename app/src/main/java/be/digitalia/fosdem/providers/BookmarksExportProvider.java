package be.digitalia.fosdem.providers;

import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.ShareCompat;
import android.text.TextUtils;

import java.io.BufferedWriter;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.TimeZone;

import be.digitalia.fosdem.BuildConfig;
import be.digitalia.fosdem.R;
import be.digitalia.fosdem.api.FosdemUrls;
import be.digitalia.fosdem.db.DatabaseManager;
import be.digitalia.fosdem.model.Event;
import be.digitalia.fosdem.utils.ICalendarWriter;
import be.digitalia.fosdem.utils.StringUtils;

/**
 * Content Provider generating the current bookmarks list in iCalendar format.
 */
public class BookmarksExportProvider extends ContentProvider {

	private static final Uri URI = new Uri.Builder()
			.scheme("content")
			.authority(BuildConfig.APPLICATION_ID + ".bookmarks")
			.appendPath("bookmarks.ics")
			.build();
	private static final String TYPE = "text/calendar";

	private static final String[] COLUMNS = {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};


	public static Intent getIntent(Activity activity) {
		final Intent exportIntent;
		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
			// Supports granting read permission for the attached shared file
			exportIntent = ShareCompat.IntentBuilder.from(activity)
					.setStream(URI)
					.setType(TYPE)
					.getIntent();
		} else {
			// Fallback: open file directly
			exportIntent = new Intent(Intent.ACTION_VIEW).setDataAndType(URI, TYPE);
		}
		exportIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		return exportIntent;
	}

	@Override
	public boolean onCreate() {
		return true;
	}

	@Nullable
	@Override
	public Uri insert(@NonNull Uri uri, ContentValues values) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int update(@NonNull Uri uri, ContentValues values, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}

	@Override
	public int delete(@NonNull Uri uri, String selection, String[] selectionArgs) {
		throw new UnsupportedOperationException();
	}

	@Nullable
	@Override
	public String getType(@NonNull Uri uri) {
		return TYPE;
	}

	@Nullable
	@Override
	public Cursor query(@NonNull Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
		if (projection == null) {
			projection = COLUMNS;
		}
		String[] cols = new String[projection.length];
		Object[] values = new Object[projection.length];
		int i = 0;
		for (String col : projection) {
			if (OpenableColumns.DISPLAY_NAME.equals(col)) {
				cols[i] = OpenableColumns.DISPLAY_NAME;
				values[i++] = getContext().getString(R.string.export_bookmarks_file_name, DatabaseManager.getInstance().getYear());
			} else if (OpenableColumns.SIZE.equals(col)) {
				cols[i] = OpenableColumns.SIZE;
				// Unknown size, content will be generated on-the-fly
				values[i++] = 1024L;
			}
		}

		cols = copyOf(cols, i);
		values = copyOf(values, i);

		final MatrixCursor cursor = new MatrixCursor(cols, 1);
		cursor.addRow(values);
		return cursor;
	}

	@TargetApi(Build.VERSION_CODES.GINGERBREAD)
	@Nullable
	@Override
	public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.GINGERBREAD) {
			throw new FileNotFoundException("Bookmarks export is not supported for this Android version");
		}

		try {
			ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
			new DownloadThread(new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])).start();
			return pipe[0];
		} catch (IOException e) {
			throw new FileNotFoundException("Could not open pipe");
		}
	}

	private static String[] copyOf(String[] original, int newLength) {
		final String[] result = new String[newLength];
		System.arraycopy(original, 0, result, 0, newLength);
		return result;
	}

	private static Object[] copyOf(Object[] original, int newLength) {
		final Object[] result = new Object[newLength];
		System.arraycopy(original, 0, result, 0, newLength);
		return result;
	}


	static class DownloadThread extends Thread {
		private final ICalendarWriter writer;

		private final DateFormat dateFormat;
		private final String dtStamp;
		private final TextUtils.StringSplitter personsSplitter = new StringUtils.SimpleStringSplitter(", ");

		DownloadThread(OutputStream out) {
			this.writer = new ICalendarWriter(new BufferedWriter(new OutputStreamWriter(out)));

			// Format all times in GMT
			this.dateFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
			this.dateFormat.setTimeZone(TimeZone.getTimeZone("GMT+0"));
			this.dtStamp = dateFormat.format(System.currentTimeMillis());
		}

		@Override
		public void run() {
			try {
				final Cursor cursor = DatabaseManager.getInstance().getBookmarks(0L);
				try {
					writer.write("BEGIN", "VCALENDAR");
					writer.write("VERSION", "2.0");
					writer.write("PRODID", "-//" + BuildConfig.APPLICATION_ID + "//NONSGML " + BuildConfig.VERSION_NAME + "//EN");

					Event event = null;
					while (cursor.moveToNext()) {
						event = DatabaseManager.toEvent(cursor, event);
						writeEvent(event);
					}

					writer.write("END", "VCALENDAR");

				} finally {
					cursor.close();
				}
			} catch (Exception ignore) {
			} finally {
				try {
					writer.close();
				} catch (IOException ignore) {
				}
			}
		}

		private void writeEvent(Event event) throws IOException {
			writer.write("BEGIN", "VEVENT");

			final int year = DatabaseManager.getInstance().getYear();
			writer.write("UID", String.format(Locale.US, "%1$d@%2$d@%3$s", event.getId(), year, BuildConfig.APPLICATION_ID));
			writer.write("DTSTAMP", dtStamp);
			if (event.getStartTime() != null) {
				writer.write("DTSTART", dateFormat.format(event.getStartTime()));
			}
			if (event.getEndTime() != null) {
				writer.write("DTEND", dateFormat.format(event.getEndTime()));
			}
			writer.write("SUMMARY", event.getTitle());
			String description = event.getAbstractText();
			if (TextUtils.isEmpty(description)) {
				description = event.getDescription();
			}
			if (!TextUtils.isEmpty(description)) {
				writer.write("DESCRIPTION", StringUtils.stripHtml(description));
				writer.write("X-ALT-DESC", description);
			}
			writer.write("CLASS", "PUBLIC");
			writer.write("CATEGORIES", event.getTrack().getName());
			writer.write("URL", event.getUrl());
			writer.write("LOCATION", event.getRoomName());

			personsSplitter.setString(event.getPersonsSummary());
			for (String name : personsSplitter) {
				String key = String.format(Locale.US, "ATTENDEE;ROLE=REQ-PARTICIPANT;CUTYPE=INDIVIDUAL;CN=\"%1$s\"", name);
				String url = FosdemUrls.getPerson(StringUtils.toSlug(name), year);
				writer.write(key, url);
			}

			writer.write("END", "VEVENT");
		}
	}
}
