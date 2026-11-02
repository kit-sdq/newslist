package edu.kit.kastel.sdq.newslist.service;

/**
 * Result of a successful upload.
 *
 * @param filename    the name the file was stored under
 * @param records     number of valid records
 * @param skippedRows number of rows skipped because they did not have exactly two columns
 * @param reloaded    whether the news index was refreshed afterwards (if not, users still see the previous news)
 */
public record UploadResult(String filename, int records, int skippedRows, boolean reloaded) {
}
