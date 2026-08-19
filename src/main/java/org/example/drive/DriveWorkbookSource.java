package org.example.drive;

import java.io.IOException;

public interface DriveWorkbookSource {
    DriveWorkbookSnapshot download() throws IOException;

    DriveWorkbookSnapshot upload(DriveWorkbookSnapshot source, PaymentDateWorkbookWriter.Result written) throws IOException;
}
