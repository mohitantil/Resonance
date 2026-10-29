using Microsoft.Data.Sqlite;
using System;
using System.Collections.Generic;
using System.IO;

namespace WindowsAgent
{
    /// <summary>
    /// Local SQLite database for clipboard journal and file transfers.
    /// Mirrors the Android Room schema.
    /// </summary>
    public class ClipboardDatabase
    {
        private const int MaxClipboardItems = 10;
        private const int MaxTransferItems = 20;
        private readonly string _connectionString;

        public ClipboardDatabase()
        {
            var dbPath = Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "sync_database.db");
            _connectionString = $"Data Source={dbPath};Default Timeout=5;Pooling=True";
            InitializeDatabase();
        }

        private void InitializeDatabase()
        {
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();

            var cmd = connection.CreateCommand();
            cmd.CommandText = @"
                PRAGMA journal_mode=WAL;
                PRAGMA synchronous=NORMAL;

                CREATE TABLE IF NOT EXISTS clipboard_items (
                    clipboardId TEXT PRIMARY KEY,
                    source      TEXT NOT NULL,
                    content     TEXT NOT NULL,
                    timestamp   INTEGER NOT NULL
                );

                CREATE TABLE IF NOT EXISTS file_transfers (
                    transferId  TEXT PRIMARY KEY,
                    fileName    TEXT NOT NULL,
                    fileSize    INTEGER NOT NULL,
                    mimeType    TEXT NOT NULL,
                    direction   TEXT NOT NULL,
                    status      TEXT NOT NULL,
                    localPath   TEXT,
                    timestamp   INTEGER NOT NULL
                );
            ";
            cmd.ExecuteNonQuery();
        }

        // ─── Clipboard operations ───────────────────────────────────────────

        public bool InsertItem(string clipboardId, string source, string content, long timestamp)
        {
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();

            var checkCmd = connection.CreateCommand();
            checkCmd.CommandText = "SELECT COUNT(*) FROM clipboard_items WHERE clipboardId = @id";
            checkCmd.Parameters.AddWithValue("@id", clipboardId);
            var exists = Convert.ToInt64(checkCmd.ExecuteScalar()) > 0;
            if (exists) return false;

            var insertCmd = connection.CreateCommand();
            insertCmd.CommandText = @"
                INSERT OR REPLACE INTO clipboard_items (clipboardId, source, content, timestamp)
                VALUES (@id, @src, @content, @ts)
            ";
            insertCmd.Parameters.AddWithValue("@id", clipboardId);
            insertCmd.Parameters.AddWithValue("@src", source);
            insertCmd.Parameters.AddWithValue("@content", content);
            insertCmd.Parameters.AddWithValue("@ts", timestamp);
            insertCmd.ExecuteNonQuery();

            EnforceClipboardCap(connection);
            return true;
        }

        public bool ExistsById(string clipboardId)
        {
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();
            var cmd = connection.CreateCommand();
            cmd.CommandText = "SELECT COUNT(*) FROM clipboard_items WHERE clipboardId = @id";
            cmd.Parameters.AddWithValue("@id", clipboardId);
            return Convert.ToInt64(cmd.ExecuteScalar()) > 0;
        }

        public List<ClipboardEntry> GetAllItems()
        {
            var items = new List<ClipboardEntry>();
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();

            var cmd = connection.CreateCommand();
            cmd.CommandText = "SELECT clipboardId, source, content, timestamp FROM clipboard_items ORDER BY timestamp DESC";
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                items.Add(new ClipboardEntry
                {
                    ClipboardId = reader.GetString(0),
                    Source = reader.GetString(1),
                    Content = reader.GetString(2),
                    Timestamp = reader.GetInt64(3)
                });
            }
            return items;
        }

        private void EnforceClipboardCap(SqliteConnection connection)
        {
            var countCmd = connection.CreateCommand();
            countCmd.CommandText = "SELECT COUNT(*) FROM clipboard_items";
            var count = Convert.ToInt64(countCmd.ExecuteScalar());

            while (count > MaxClipboardItems)
            {
                var deleteCmd = connection.CreateCommand();
                deleteCmd.CommandText = @"
                    DELETE FROM clipboard_items 
                    WHERE clipboardId = (
                        SELECT clipboardId FROM clipboard_items ORDER BY timestamp ASC LIMIT 1
                    )
                ";
                deleteCmd.ExecuteNonQuery();
                count--;
            }
        }

        // ─── File Transfer operations ───────────────────────────────────────

        public void InsertTransfer(TransferEntry entry)
        {
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();

            var cmd = connection.CreateCommand();
            cmd.CommandText = @"
                INSERT OR REPLACE INTO file_transfers (transferId, fileName, fileSize, mimeType, direction, status, localPath, timestamp)
                VALUES (@id, @name, @size, @mime, @dir, @status, @path, @ts)
            ";
            cmd.Parameters.AddWithValue("@id", entry.TransferId);
            cmd.Parameters.AddWithValue("@name", entry.FileName);
            cmd.Parameters.AddWithValue("@size", entry.FileSize);
            cmd.Parameters.AddWithValue("@mime", entry.MimeType);
            cmd.Parameters.AddWithValue("@dir", entry.Direction);
            cmd.Parameters.AddWithValue("@status", entry.Status);
            cmd.Parameters.AddWithValue("@path", (object?)entry.LocalPath ?? DBNull.Value);
            cmd.Parameters.AddWithValue("@ts", entry.Timestamp);
            cmd.ExecuteNonQuery();

            EnforceTransferCap(connection);
        }

        public List<TransferEntry> GetAllTransfers()
        {
            var items = new List<TransferEntry>();
            using var connection = new SqliteConnection(_connectionString);
            connection.Open();

            var cmd = connection.CreateCommand();
            cmd.CommandText = "SELECT transferId, fileName, fileSize, mimeType, direction, status, localPath, timestamp FROM file_transfers ORDER BY timestamp DESC";
            using var reader = cmd.ExecuteReader();
            while (reader.Read())
            {
                items.Add(new TransferEntry
                {
                    TransferId = reader.GetString(0),
                    FileName = reader.GetString(1),
                    FileSize = reader.GetInt64(2),
                    MimeType = reader.GetString(3),
                    Direction = reader.GetString(4),
                    Status = reader.GetString(5),
                    LocalPath = reader.IsDBNull(6) ? null : reader.GetString(6),
                    Timestamp = reader.GetInt64(7)
                });
            }
            return items;
        }

        private void EnforceTransferCap(SqliteConnection connection)
        {
            var countCmd = connection.CreateCommand();
            countCmd.CommandText = "SELECT COUNT(*) FROM file_transfers";
            var count = Convert.ToInt64(countCmd.ExecuteScalar());

            while (count > MaxTransferItems)
            {
                var deleteCmd = connection.CreateCommand();
                deleteCmd.CommandText = @"
                    DELETE FROM file_transfers 
                    WHERE transferId = (
                        SELECT transferId FROM file_transfers ORDER BY timestamp ASC LIMIT 1
                    )
                ";
                deleteCmd.ExecuteNonQuery();
                count--;
            }
        }
    }

    public class ClipboardEntry
    {
        public string ClipboardId { get; set; } = "";
        public string Source { get; set; } = "";
        public string Content { get; set; } = "";
        public long Timestamp { get; set; }
    }

    public class TransferEntry
    {
        public string TransferId { get; set; } = "";
        public string FileName { get; set; } = "";
        public long FileSize { get; set; }
        public string MimeType { get; set; } = "";
        public string Direction { get; set; } = ""; // "sent" or "received"
        public string Status { get; set; } = "";    // "completed", "failed", "in_progress"
        public string? LocalPath { get; set; }
        public long Timestamp { get; set; }
    }
}
