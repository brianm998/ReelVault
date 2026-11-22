// SPDX-License-Identifier: GPL-3.0-or-later
// Copyright (C) 2026 ReelVault Contributors

//! Grid / Library Filter query benchmark on a synthetic 100k-video catalog:
//! 10% of videos in stacks of 3, 5% proxies, 60 tags, a metadata row each.
//!
//! `cargo bench --bench catalog_queries` (set `RV_BENCH_VIDEOS` to resize).

use criterion::{criterion_group, criterion_main, Criterion};
use reelvault_core::db::{Database, FilterSpec};
use rusqlite::params;

fn build(n: usize) -> (tempfile::TempDir, Database) {
    let tmp = tempfile::tempdir().unwrap();
    let db = Database::new_empty();
    db.set_path(&tmp.path().join("bench.db")).unwrap();
    let mut conn = db.get_connection().unwrap();
    let tx = conn.transaction().unwrap();
    let cams = ["Sony FX3", "Sony A7S III", "Canon R5", "iPhone 15 Pro", "DJI Mini 4"];
    let codecs = ["prores", "h264", "hevc"];
    for i in 0..n {
        let id = format!("v{i:07}");
        // First 10% of ids form stacks of 3; the next 5% are proxies of earlier videos.
        let stacked = i < n / 10;
        let group = if stacked { Some(format!("g{:06}", i / 3)) } else { None };
        let proxy_of = if i >= n / 10 && i < n / 10 + n / 20 {
            Some(format!("v{:07}", (i - n / 10) * 5))
        } else {
            None
        };
        if stacked && i % 3 == 0 {
            tx.execute(
                "INSERT INTO video_groups (id, base_name) VALUES (?, ?)",
                params![group.as_ref().unwrap(), format!("clip{i}")],
            )
            .unwrap();
        }
        tx.execute(
            "INSERT INTO videos (id, path, filename, file_size_bytes, indexed_at, group_id, proxy_of)
             VALUES (?, ?, ?, ?, ?, ?, ?)",
            params![id, format!("/lib/d{}/clip{i}.mov", i % 200), format!("clip{i}.mov"),
                    (i * 7919 % 1_000_000) as i64, i as i64, group, proxy_of],
        )
        .unwrap();
        if stacked && i % 3 == 0 {
            tx.execute(
                "UPDATE video_groups SET preferred_video_id = ? WHERE id = ?",
                params![id, group.as_ref().unwrap()],
            )
            .unwrap();
        }
        tx.execute(
            "INSERT INTO metadata (video_id, duration_ms, codec_video, width, height, fps, camera_model)
             VALUES (?, ?, ?, 3840, 2160, 24.0, ?)",
            params![id, (i * 31 % 600_000) as i64, codecs[i % 3], cams[i % 5]],
        )
        .unwrap();
    }
    for t in 0..60 {
        tx.execute("INSERT INTO tags (id, name) VALUES (?, ?)", params![format!("t{t}"), format!("tag{t:02}")])
            .unwrap();
    }
    for i in (0..n).step_by(2) {
        tx.execute(
            "INSERT INTO video_tags (video_id, tag_id) VALUES (?, ?)",
            params![format!("v{i:07}"), format!("t{}", i % 60)],
        )
        .unwrap();
    }
    tx.commit().unwrap();
    drop(conn);
    (tmp, db)
}

fn bench(c: &mut Criterion) {
    let n = std::env::var("RV_BENCH_VIDEOS").ok().and_then(|v| v.parse().ok()).unwrap_or(100_000);
    let (_tmp, db) = build(n);
    let none = FilterSpec::default();
    let mut g = c.benchmark_group("catalog_100k");
    g.sample_size(10);

    // Cold: a fresh filter every iteration forces the count (first page).
    let mut k = 0;
    g.bench_function("grid_first_page_count_miss", |b| {
        b.iter(|| {
            k += 1;
            let spec = FilterSpec { search_query: String::new(), min_rating: 0,
                metadata_filters: vec![("camera".into(), format!("nobody{k}"))], ..Default::default() };
            db.list_videos_grouped(100, 0, "name", true, &spec).unwrap()
        })
    });
    // The unfiltered count as the grid ran it before (joins always on) and now.
    let conn = db.get_connection().unwrap();
    let rep = reelvault_core::db::REPRESENTATIVE_FILTER;
    let old = format!("SELECT COUNT(*) FROM videos v LEFT JOIN metadata m ON v.id = m.video_id LEFT JOIN video_user_marks um ON v.id = um.video_id WHERE ({rep}) AND v.deleted_at IS NULL");
    let new = format!("SELECT COUNT(*) FROM videos v WHERE ({rep}) AND v.deleted_at IS NULL");
    g.bench_function("count_unfiltered_with_joins", |b| {
        b.iter(|| conn.query_row(&old, [], |r| r.get::<_, i64>(0)).unwrap())
    });
    g.bench_function("count_unfiltered_pruned", |b| {
        b.iter(|| conn.query_row(&new, [], |r| r.get::<_, i64>(0)).unwrap())
    });
    db.list_videos_grouped(100, 0, "name", true, &none).unwrap();
    g.bench_function("grid_later_page_count_cached", |b| {
        b.iter(|| db.list_videos_grouped(100, 5000, "name", true, &none).unwrap())
    });
    g.bench_function("facet_5_columns", |b| {
        b.iter(|| {
            for key in ["camera", "codec", "keyword", "year", "lens"] {
                db.distinct_facet_values(key, &none).unwrap();
            }
        })
    });
    g.finish();
}

criterion_group!(benches, bench);
criterion_main!(benches);
