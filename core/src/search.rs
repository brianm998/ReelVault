// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

use crate::db::Database;
use crate::error::{Result, ReelVaultError};

#[derive(Debug, Clone)]
pub struct SearchResult {
    pub video_id: String,
    pub filename: String,
    pub path: String,
}

pub struct SearchEngine;

impl SearchEngine {
    pub fn search(
        db: &Database,
        query: &str,
        limit: i64,
        offset: i64,
        filter_tags: &[String],
    ) -> Result<(Vec<SearchResult>, i64)> {
        let conn = db.get_connection()?;

        // Build base query with FTS5
        let mut sql = String::from(
            "SELECT DISTINCT v.id, v.filename, v.path
             FROM videos v
             LEFT JOIN video_tags vt ON v.id = vt.video_id
             LEFT JOIN video_notes vn ON v.id = vn.video_id
             WHERE (v.filename LIKE ? OR vn.notes LIKE ?)
        "
        );

        let like_query = format!("%{}%", query);

        // Add tag filters if provided
        if !filter_tags.is_empty() {
            sql.push_str(" AND (");
            for (i, _tag) in filter_tags.iter().enumerate() {
                if i > 0 {
                    sql.push_str(" OR ");
                }
                sql.push_str("vt.tag_id = ?");
            }
            sql.push(')');
        }

        sql.push_str(" ORDER BY v.indexed_at DESC LIMIT ? OFFSET ?");

        let mut stmt = conn
            .prepare(&sql)
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;

        let mut params_vec: Vec<&dyn rusqlite::ToSql> = vec![&like_query, &like_query];

        // Add tag parameters
        let tag_ids: Vec<String> = filter_tags.to_vec();
        for tag_id in &tag_ids {
            params_vec.push(tag_id);
        }
        params_vec.push(&limit);
        params_vec.push(&offset);

        let results = stmt
            .query_map(params_vec.as_slice(), |row| {
                Ok(SearchResult {
                    video_id: row.get(0)?,
                    filename: row.get(1)?,
                    path: row.get(2)?,
                })
            })
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;

        // Get total count
        let count_sql = format!(
            "SELECT COUNT(DISTINCT v.id) FROM videos v
             LEFT JOIN video_tags vt ON v.id = vt.video_id
             LEFT JOIN video_notes vn ON v.id = vn.video_id
             WHERE (v.filename LIKE ? OR vn.notes LIKE ?){}",
            if !filter_tags.is_empty() {
                let mut tag_sql = String::from(" AND (");
                for (i, _) in filter_tags.iter().enumerate() {
                    if i > 0 {
                        tag_sql.push_str(" OR ");
                    }
                    tag_sql.push_str("vt.tag_id = ?");
                }
                tag_sql.push(')');
                tag_sql
            } else {
                String::new()
            }
        );

        let mut count_stmt = conn
            .prepare(&count_sql)
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;

        let count_params: Vec<&dyn rusqlite::ToSql> = {
            let mut p: Vec<&dyn rusqlite::ToSql> = vec![&like_query, &like_query];
            for tag_id in &tag_ids {
                p.push(tag_id);
            }
            p
        };

        let total: i64 = count_stmt
            .query_row(count_params.as_slice(), |row| row.get(0))
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;

        Ok((results, total))
    }

    pub fn advanced_search(
        db: &Database,
        filters: &SearchFilters,
        limit: i64,
        offset: i64,
    ) -> Result<(Vec<SearchResult>, i64)> {
        let conn = db.get_connection()?;

        // Build dynamic query based on filters
        let mut sql = String::from(
            "SELECT DISTINCT v.id, v.filename, v.path
             FROM videos v
             LEFT JOIN metadata m ON v.id = m.video_id
             LEFT JOIN video_tags vt ON v.id = vt.video_id
             WHERE 1=1"
        );

        let mut params: Vec<Box<dyn rusqlite::ToSql>> = Vec::new();

        // Filter by filename
        if let Some(filename) = &filters.filename {
            sql.push_str(" AND v.filename LIKE ?");
            params.push(Box::new(format!("%{}%", filename)));
        }

        // Filter by resolution
        if let Some(min_width) = filters.min_width {
            sql.push_str(" AND m.width >= ?");
            params.push(Box::new(min_width));
        }
        if let Some(max_width) = filters.max_width {
            sql.push_str(" AND m.width <= ?");
            params.push(Box::new(max_width));
        }

        // Filter by codec
        if let Some(codec) = &filters.codec {
            sql.push_str(" AND (m.codec_video = ? OR m.codec_audio = ?)");
            params.push(Box::new(codec.clone()));
            params.push(Box::new(codec.clone()));
        }

        // Filter by duration
        if let Some(min_duration) = filters.min_duration_ms {
            sql.push_str(" AND m.duration_ms >= ?");
            params.push(Box::new(min_duration));
        }
        if let Some(max_duration) = filters.max_duration_ms {
            sql.push_str(" AND m.duration_ms <= ?");
            params.push(Box::new(max_duration));
        }

        // Filter by FPS
        if let Some(fps) = filters.fps {
            let tolerance = 0.5;
            sql.push_str(&format!(" AND m.fps BETWEEN {} AND {}", fps - tolerance, fps + tolerance));
        }

        // Filter by tags
        if !filters.tags.is_empty() {
            sql.push_str(" AND vt.tag_id IN (");
            for (i, _) in filters.tags.iter().enumerate() {
                if i > 0 {
                    sql.push(',');
                }
                sql.push('?');
            }
            sql.push(')');
            for tag_id in &filters.tags {
                params.push(Box::new(tag_id.clone()));
            }
        }

        sql.push_str(" ORDER BY v.indexed_at DESC LIMIT ? OFFSET ?");
        params.push(Box::new(limit));
        params.push(Box::new(offset));

        let param_refs: Vec<&dyn rusqlite::ToSql> = params.iter().map(|p| p.as_ref()).collect();

        let mut stmt = conn
            .prepare(&sql)
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;

        let results = stmt
            .query_map(param_refs.as_slice(), |row| {
                Ok(SearchResult {
                    video_id: row.get(0)?,
                    filename: row.get(1)?,
                    path: row.get(2)?,
                })
            })
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?
            .collect::<std::result::Result<Vec<_>, _>>()
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;

        // Get total count
        let count_sql = sql.replace("SELECT DISTINCT v.id, v.filename, v.path", "SELECT COUNT(DISTINCT v.id)")
            .split("LIMIT")
            .next()
            .unwrap_or("")
            .to_string();

        let count_params: Vec<&dyn rusqlite::ToSql> = params
            .iter()
            .take(params.len() - 2)
            .map(|p| p.as_ref())
            .collect();

        let mut count_stmt = conn
            .prepare(&count_sql)
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;

        let total: i64 = count_stmt
            .query_row(count_params.as_slice(), |row| row.get(0))
            .map_err(|e| ReelVaultError::DatabaseError(e.to_string()))?;

        Ok((results, total))
    }
}

#[derive(Debug, Default, Clone)]
pub struct SearchFilters {
    pub filename: Option<String>,
    pub min_width: Option<i32>,
    pub max_width: Option<i32>,
    pub codec: Option<String>,
    pub min_duration_ms: Option<i64>,
    pub max_duration_ms: Option<i64>,
    pub fps: Option<f64>,
    pub tags: Vec<String>,
}
