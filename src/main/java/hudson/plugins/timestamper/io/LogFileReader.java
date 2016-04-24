/*
 * The MIT License
 * 
 * Copyright (c) 2016 Steven G. Brown
 * 
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * 
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 * 
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package hudson.plugins.timestamper.io;

import static com.google.common.base.Preconditions.checkNotNull;
import hudson.console.ConsoleNote;
import hudson.model.Run;
import hudson.plugins.timestamper.Timestamp;
import hudson.plugins.timestamper.TimestampNote;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.Charset;

import javax.annotation.CheckForNull;

import com.google.common.base.Objects;
import com.google.common.base.Optional;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * Reader for the build log file which skips over the console notes.
 * 
 * @author Steven G. Brown
 */
public class LogFileReader {

  public static class Line {

    public final String contents;

    public final Optional<Timestamp> timestamp;

    public Line(String contents, Optional<Timestamp> timestamp) {
      this.contents = checkNotNull(contents);
      this.timestamp = checkNotNull(timestamp);
    }

    @Override
    public int hashCode() {
      return Objects.hashCode(contents, timestamp);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj instanceof Line) {
        Line other = (Line) obj;
        return contents.equals(other.contents)
            && timestamp.equals(other.timestamp);
      }
      return false;
    }

    @Override
    public String toString() {
      return Objects.toStringHelper(this).add("contents", contents)
          .add("timestamp", timestamp).toString();
    }
  }

  private final Run<?, ?> build;

  @CheckForNull
  private BufferedReader reader;

  /**
   * Create a log file reader for the given build.
   * 
   * @param build
   */
  public LogFileReader(Run<?, ?> build) {
    this.build = checkNotNull(build);
  }

  /**
   * Read the next line from the log file.
   * 
   * @return the next line, or {@link Optional#absent()} if there are no more to
   *         read
   * @throws IOException
   */
  public Optional<Line> nextLine() throws IOException {
    if (!build.getLogFile().exists()) {
      return Optional.absent();
    }
    if (reader == null) {
      reader = new BufferedReader(build.getLogReader());
    }
    String line = reader.readLine();
    if (line == null) {
      return Optional.absent();
    }
    Optional<Timestamp> timestamp = readTimestamp(line);
    line = ConsoleNote.removeNotes(line);
    return Optional.of(new Line(line, timestamp));
  }

  private Optional<Timestamp> readTimestamp(String line) throws IOException {
    Charset charset = build.getCharset();
    DataInputStream dataInputStream = new DataInputStream(
        new ByteArrayInputStream(line.getBytes(charset)));
    try {
      while (true) {
        dataInputStream.mark(1);
        int currentByte = dataInputStream.read();
        if (currentByte == -1) {
          return Optional.absent();
        }
        if (currentByte == ConsoleNote.PREAMBLE[0]) {
          dataInputStream.reset();
          ConsoleNote<?> consoleNote;
          try {
            consoleNote = ConsoleNote.readFrom(dataInputStream);
          } catch (ClassNotFoundException ex) {
            // Unknown console note. Ignore.
            continue;
          }
          if (consoleNote instanceof TimestampNote) {
            TimestampNote timestampNote = (TimestampNote) consoleNote;
            Timestamp timestamp = timestampNote.getTimestamp(build);
            return Optional.of(timestamp);
          }
        }
      }
    } finally {
      Closeables.closeQuietly(dataInputStream);
    }
  }

  /**
   * Get the number of lines that can be read from the log file.
   * 
   * @return the line count
   * @throws IOException
   */
  @SuppressFBWarnings("RV_DONT_JUST_NULL_CHECK_READLINE")
  public int lineCount() throws IOException {
    if (!build.getLogFile().exists()) {
      return 0;
    }
    int lineCount = 0;
    BufferedReader reader = new BufferedReader(build.getLogReader());
    try {
      while (reader.readLine() != null) {
        lineCount++;
      }
    } finally {
      Closeables.closeQuietly(reader);
    }
    return lineCount;
  }

  /**
   * Close this reader.
   */
  public void close() {
    Closeables.closeQuietly(reader);
  }
}
