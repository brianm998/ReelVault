// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Sparse content hashing for catalog sync.
//!
//! A "sparse" hash covers:
//!   - the first 8 MiB of the file
//!   - the last 8 MiB of the file  (omitted when total < 16 MiB; instead the
//!     entire file is hashed)
//!   - the total file size as 8 little-endian bytes
//!
//! This gives a strong identity signal (two files with the same sparse hash are
//! almost certainly the same content) while reading at most 16 MiB even for
//! multi-terabyte video files. Used by the catalog sync engine to decide whether
//! a remote video already exists locally without transferring the whole file.

use std::io::{Read, Seek, SeekFrom};
use std::path::Path;

use crate::error::{ReelVaultError, Result};

const CHUNK: u64 = 8 * 1024 * 1024; // 8 MiB

/// Compute the sparse blake3 hash of `path` and return it as a lowercase hex
/// string. For files smaller than 16 MiB the entire content is hashed.
pub fn sparse_content_hash(path: &Path) -> Result<String> {
    let mut f = std::fs::File::open(path)
        .map_err(|e| ReelVaultError::InternalError(format!("open {}: {e}", path.display())))?;
    sparse_content_hash_of_reader(&mut f)
}

/// Same as [`sparse_content_hash`] but operates on any [`Read`] + [`Seek`].
pub fn sparse_content_hash_of_reader<R: Read + Seek>(r: &mut R) -> Result<String> {
    let total = r
        .seek(SeekFrom::End(0))
        .map_err(|e| ReelVaultError::InternalError(format!("seek end: {e}")))?;
    r.seek(SeekFrom::Start(0))
        .map_err(|e| ReelVaultError::InternalError(format!("seek start: {e}")))?;

    let mut hasher = blake3::Hasher::new();
    let mut buf = vec![0u8; 64 * 1024]; // 64 KiB I/O buffer

    if total <= 2 * CHUNK {
        // Small file: hash everything.
        loop {
            let n = r
                .read(&mut buf)
                .map_err(|e| ReelVaultError::InternalError(format!("read: {e}")))?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
    } else {
        // Large file: hash first CHUNK bytes.
        let mut remaining = CHUNK;
        loop {
            let want = (remaining as usize).min(buf.len());
            let n = r
                .read(&mut buf[..want])
                .map_err(|e| ReelVaultError::InternalError(format!("read head: {e}")))?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
            remaining -= n as u64;
            if remaining == 0 {
                break;
            }
        }

        // Seek to last CHUNK bytes and hash them.
        r.seek(SeekFrom::Start(total - CHUNK))
            .map_err(|e| ReelVaultError::InternalError(format!("seek tail: {e}")))?;
        loop {
            let n = r
                .read(&mut buf)
                .map_err(|e| ReelVaultError::InternalError(format!("read tail: {e}")))?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
    }

    // Mix in the total size as a little-endian u64 so files that differ only in
    // size (but happen to share the first+last 8 MiB — e.g. two recordings of
    // different length from the same camera) still get distinct hashes.
    hasher.update(&total.to_le_bytes());

    Ok(hasher.finalize().to_hex().to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn small_file_deterministic() {
        let data = b"hello, ReelVault!";
        let h1 = sparse_content_hash_of_reader(&mut Cursor::new(data)).unwrap();
        let h2 = sparse_content_hash_of_reader(&mut Cursor::new(data)).unwrap();
        assert_eq!(h1, h2);
        assert_eq!(h1.len(), 64); // blake3 hex is 64 chars
    }

    #[test]
    fn different_sizes_differ() {
        let a = vec![0u8; 1024];
        let b = vec![0u8; 2048];
        let ha = sparse_content_hash_of_reader(&mut Cursor::new(&a)).unwrap();
        let hb = sparse_content_hash_of_reader(&mut Cursor::new(&b)).unwrap();
        assert_ne!(ha, hb);
    }
}
