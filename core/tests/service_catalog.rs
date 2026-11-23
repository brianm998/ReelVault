//! In-process gRPC service tests against a temp catalog (no ffmpeg).
mod common;

use common::Harness;
use reelvault_core::service::reelvault::reel_vault_server::ReelVault;
use reelvault_core::service::reelvault::*;
use tonic::{Code, Request};

fn list_req(limit: i32, offset: i32, sort: &str, asc: bool) -> ListVideosRequest {
    ListVideosRequest {
        limit,
        offset,
        sort_by: sort.into(),
        sort_ascending: asc,
        ..Default::default()
    }
}

async fn names(h: &Harness, r: ListVideosRequest) -> Vec<String> {
    h.svc.list_videos(Request::new(r)).await.unwrap().into_inner().videos.into_iter().map(|v| v.filename).collect()
}

async fn seeded() -> Harness {
    let h = Harness::new().await;
    h.add_video("b.mp4", 20);
    h.add_video("a.mp4", 30);
    h.add_video("c.mp4", 10);
    h
}

#[tokio::test]
async fn list_sorts_by_name_both_directions() {
    let h = seeded().await;
    assert_eq!(names(&h, list_req(10, 0, "name", true)).await, ["a.mp4", "b.mp4", "c.mp4"]);
    assert_eq!(names(&h, list_req(10, 0, "name", false)).await, ["c.mp4", "b.mp4", "a.mp4"]);
}

#[tokio::test]
async fn list_sorts_by_size() {
    let h = seeded().await;
    assert_eq!(names(&h, list_req(10, 0, "size", true)).await, ["c.mp4", "b.mp4", "a.mp4"]);
}

#[tokio::test]
async fn list_paging_reports_total_and_has_more() {
    let h = seeded().await;
    let r = h.svc.list_videos(Request::new(list_req(2, 0, "name", true))).await.unwrap().into_inner();
    assert_eq!((r.videos.len(), r.total_count, r.has_more), (2, 3, true));
    let r = h.svc.list_videos(Request::new(list_req(2, 2, "name", true))).await.unwrap().into_inner();
    assert_eq!((r.videos.len(), r.total_count, r.has_more), (1, 3, false));
    assert_eq!(r.videos[0].filename, "c.mp4");
}

#[tokio::test]
async fn list_zero_limit_defaults_and_negative_offset_clamps() {
    let h = seeded().await;
    let r = h.svc.list_videos(Request::new(list_req(0, -5, "name", true))).await.unwrap().into_inner();
    assert_eq!(r.videos.len(), 3);
}

#[tokio::test]
async fn list_unknown_sort_does_not_error_or_inject() {
    let h = seeded().await;
    let r = names(&h, list_req(10, 0, "name; DROP TABLE videos;--", true)).await;
    assert_eq!(r.len(), 3);
    assert_eq!(names(&h, list_req(10, 0, "name", true)).await.len(), 3);
}

#[tokio::test]
async fn list_filters_by_rating_and_color() {
    let h = seeded().await;
    let ids: Vec<_> = h.db.list_videos(10, 0).unwrap().0.into_iter().map(|v| (v.filename, v.id)).collect();
    let id = |n: &str| ids.iter().find(|(f, _)| f == n).unwrap().1.clone();
    h.db.update_video_rating(&id("a.mp4"), 5).unwrap();
    h.db.update_video_rating(&id("b.mp4"), 2).unwrap();
    h.db.update_video_color_label(&id("b.mp4"), "red").unwrap();
    let mut r = list_req(10, 0, "name", true);
    r.filter_min_rating = 3;
    assert_eq!(names(&h, r).await, ["a.mp4"]);
    let mut r = list_req(10, 0, "name", true);
    r.filter_color_label = "red".into();
    assert_eq!(names(&h, r).await, ["b.mp4"]);
}

#[tokio::test]
async fn list_filters_by_tag_name_or_id() {
    let h = seeded().await;
    let vid = h.db.list_videos(10, 0).unwrap().0[0].clone();
    let tag = h.svc.create_tag(Request::new(CreateTagRequest { name: "beach".into(), color: String::new() })).await.unwrap().into_inner();
    h.svc.tag_videos(Request::new(TagVideosRequest { video_ids: vec![vid.id.clone()], tag_id: tag.id.clone() })).await.unwrap();
    for key in ["beach", tag.id.as_str()] {
        let mut r = list_req(10, 0, "name", true);
        r.filter_tags = vec![key.into()];
        assert_eq!(names(&h, r).await, [vid.filename.clone()], "{key}");
    }
}

#[tokio::test]
async fn list_filters_by_location_path() {
    let h = Harness::new().await;
    h.db.add_video("/x/one/a.mp4", "a.mp4", None, None, Some(1)).unwrap();
    h.db.add_video("/x/two/b.mp4", "b.mp4", None, None, Some(1)).unwrap();
    let mut r = list_req(10, 0, "name", true);
    r.location_path = "/x/one".into();
    assert_eq!(names(&h, r).await, ["a.mp4"]);
    let mut r = list_req(10, 0, "name", true);
    r.location_path = "/x/one\n/x/two".into();
    assert_eq!(names(&h, r).await.len(), 2);
}

#[tokio::test]
async fn search_matches_filename_substring() {
    let h = seeded().await;
    let r = h.svc.search_videos(Request::new(SearchRequest { query: "b.m".into(), limit: 10, offset: 0, filter_tags: vec![] })).await.unwrap().into_inner();
    assert_eq!(r.videos.len(), 1);
    assert_eq!(r.videos[0].filename, "b.mp4");
}

#[tokio::test]
async fn tags_are_idempotent_by_name_and_counted() {
    let h = seeded().await;
    let t1 = h.svc.create_tag(Request::new(CreateTagRequest { name: "k".into(), color: "#fff".into() })).await.unwrap().into_inner();
    let t2 = h.svc.create_tag(Request::new(CreateTagRequest { name: "k".into(), color: String::new() })).await.unwrap().into_inner();
    assert_eq!(t1.id, t2.id);
    let vids: Vec<String> = h.db.list_videos(10, 0).unwrap().0.into_iter().map(|v| v.id).collect();
    h.svc.tag_videos(Request::new(TagVideosRequest { video_ids: vids[..2].to_vec(), tag_id: t1.id.clone() })).await.unwrap();
    let tags = h.svc.list_tags(Request::new(ListTagsRequest {})).await.unwrap().into_inner().tags;
    assert_eq!(tags.len(), 1);
    assert_eq!(tags[0].video_count, 2);
    h.svc.untag_videos(Request::new(UntagVideosRequest { video_ids: vids[..1].to_vec(), tag_id: t1.id.clone() })).await.unwrap();
    let tags = h.svc.list_tags(Request::new(ListTagsRequest {})).await.unwrap().into_inner().tags;
    assert_eq!(tags[0].video_count, 1);
}

#[tokio::test]
async fn delete_tag_removes_it_but_keeps_videos() {
    let h = seeded().await;
    let t = h.svc.create_tag(Request::new(CreateTagRequest { name: "gone".into(), color: String::new() })).await.unwrap().into_inner();
    let vid = h.db.list_videos(10, 0).unwrap().0[0].id.clone();
    h.db.tag_video(&vid, &t.id).unwrap();
    h.svc.delete_tag(Request::new(DeleteTagRequest { tag_id: t.id.clone() })).await.unwrap();
    assert!(h.db.list_tags().unwrap().is_empty());
    assert!(h.db.get_video(&vid).unwrap().is_some());
    assert!(h.db.get_video_tags(&vid).unwrap().is_empty());
}

#[tokio::test]
async fn collections_add_remove_count_and_filter() {
    let h = seeded().await;
    let c = h.svc.create_collection(Request::new(CreateCollectionRequest { name: "Fav".into(), is_smart: false, filter_json: String::new() })).await.unwrap().into_inner();
    let vids: Vec<_> = h.db.list_videos(10, 0).unwrap().0;
    let ids: Vec<String> = vids.iter().map(|v| v.id.clone()).collect();
    h.svc.add_to_collection(Request::new(AddToCollectionRequest { collection_id: c.id.clone(), video_ids: ids[..2].to_vec() })).await.unwrap();
    let cols = h.svc.list_collections(Request::new(ListCollectionsRequest {})).await.unwrap().into_inner().collections;
    assert_eq!(cols[0].video_count, 2);
    let mut r = list_req(10, 0, "name", true);
    r.collection_id = c.id.clone();
    assert_eq!(names(&h, r).await.len(), 2);
    h.svc.remove_from_collection(Request::new(RemoveFromCollectionRequest { collection_id: c.id.clone(), video_ids: ids[..1].to_vec() })).await.unwrap();
    let cols = h.svc.list_collections(Request::new(ListCollectionsRequest {})).await.unwrap().into_inner().collections;
    assert_eq!(cols[0].video_count, 1);
    h.svc.delete_collection(Request::new(DeleteCollectionRequest { collection_id: c.id })).await.unwrap();
    assert!(h.db.list_collections().unwrap().is_empty());
    assert_eq!(h.db.list_videos(10, 0).unwrap().1, 3);
}

#[tokio::test]
async fn smart_collection_keeps_filter_json_verbatim() {
    let h = Harness::new().await;
    let json = r#"{"minRating":3}"#;
    let c = h.svc.create_collection(Request::new(CreateCollectionRequest { name: "S".into(), is_smart: true, filter_json: json.into() })).await.unwrap().into_inner();
    let cols = h.svc.list_collections(Request::new(ListCollectionsRequest {})).await.unwrap().into_inner().collections;
    assert!(cols.iter().any(|x| x.id == c.id && x.is_smart && x.filter_json == json));
}

#[tokio::test]
async fn delete_video_soft_deletes_and_hides_from_listing() {
    let h = seeded().await;
    let v = h.db.list_videos(10, 0).unwrap().0[0].clone();
    h.svc.delete_video(Request::new(DeleteVideoRequest { video_id: v.id.clone(), delete_file: false })).await.unwrap();
    assert_eq!(names(&h, list_req(10, 0, "name", true)).await.len(), 2);
    let r = h.svc.list_videos(Request::new(list_req(10, 0, "name", true))).await.unwrap().into_inner();
    assert_eq!(r.total_count, 2);
}

#[tokio::test]
async fn delete_video_with_delete_file_removes_only_that_file() {
    let h = Harness::new().await;
    let f1 = h.dir.path().join("one.mp4");
    let f2 = h.dir.path().join("two.mp4");
    std::fs::write(&f1, b"1").unwrap();
    std::fs::write(&f2, b"2").unwrap();
    let id = h.db.add_video(f1.to_str().unwrap(), "one.mp4", None, None, Some(1)).unwrap();
    h.db.add_video(f2.to_str().unwrap(), "two.mp4", None, None, Some(1)).unwrap();
    h.svc.delete_video(Request::new(DeleteVideoRequest { video_id: id, delete_file: true })).await.unwrap();
    assert!(!f1.exists());
    assert!(f2.exists());
}

#[tokio::test]
async fn delete_video_without_flag_keeps_file() {
    let h = Harness::new().await;
    let f = h.dir.path().join("keep.mp4");
    std::fs::write(&f, b"1").unwrap();
    let id = h.db.add_video(f.to_str().unwrap(), "keep.mp4", None, None, Some(1)).unwrap();
    h.svc.delete_video(Request::new(DeleteVideoRequest { video_id: id, delete_file: false })).await.unwrap();
    assert!(f.exists());
}

#[tokio::test]
async fn rating_validation() {
    let h = seeded().await;
    let id = h.db.list_videos(10, 0).unwrap().0[0].id.clone();
    let bad = h.svc.update_video_rating(Request::new(UpdateVideoRatingRequest { video_ids: vec![id.clone()], rating: 6 })).await.unwrap_err();
    assert_eq!(bad.code(), Code::InvalidArgument);
    let empty = h.svc.update_video_rating(Request::new(UpdateVideoRatingRequest { video_ids: vec![], rating: 3 })).await.unwrap_err();
    assert_eq!(empty.code(), Code::InvalidArgument);
    h.svc.update_video_rating(Request::new(UpdateVideoRatingRequest { video_ids: vec![id.clone()], rating: 4 })).await.unwrap();
    assert_eq!(h.db.get_video_user_marks(&id).unwrap().0, 4);
}

#[tokio::test]
async fn stack_create_lists_members_and_groups_listing() {
    let h = seeded().await;
    let ids: Vec<String> = h.db.list_videos(10, 0).unwrap().0.into_iter().map(|v| v.id).collect();
    let g = h.svc.create_group(Request::new(CreateGroupRequest { video_ids: ids[..2].to_vec(), name: "S".into(), preferred_video_id: ids[1].clone() })).await.unwrap().into_inner();
    assert_eq!(g.size, 2);
    assert_eq!(g.preferred_video_id, ids[1]);
    let m = h.svc.list_group_members(Request::new(ListGroupMembersRequest { group_id: g.id.clone() })).await.unwrap().into_inner();
    assert_eq!(m.members.len(), 2);
    // Listing shows one representative for the stack + the ungrouped video.
    let r = h.svc.list_videos(Request::new(list_req(10, 0, "name", true))).await.unwrap().into_inner();
    assert_eq!(r.total_count, 2);
}

#[tokio::test]
async fn stack_empty_is_error_and_ungroup_dissolves() {
    let h = seeded().await;
    assert!(h.svc.create_group(Request::new(CreateGroupRequest::default())).await.is_err());
    let ids: Vec<String> = h.db.list_videos(10, 0).unwrap().0.into_iter().map(|v| v.id).collect();
    let g = h.svc.create_group(Request::new(CreateGroupRequest { video_ids: ids[..2].to_vec(), ..Default::default() })).await.unwrap().into_inner();
    h.svc.ungroup_video(Request::new(UngroupVideoRequest { video_id: ids[0].clone() })).await.unwrap();
    assert!(h.db.get_video_group_id(&ids[0]).unwrap().is_none());
    let _ = g;
}

#[tokio::test]
async fn proxy_link_set_list_remove() {
    let h = Harness::new().await;
    let master = h.add_video("m.mov", 100);
    let proxy = h.add_video("m_proxy.mp4", 10);
    h.svc.set_proxy_of(Request::new(SetProxyOfRequest { proxy_id: proxy.clone(), original_id: master.clone() })).await.unwrap();
    let p = h.svc.list_proxies(Request::new(ListProxiesRequest { video_id: master.clone() })).await.unwrap().into_inner();
    assert_eq!(p.proxies.len(), 1);
    // The proxy is hidden from the grid under its source.
    assert_eq!(names(&h, list_req(10, 0, "name", true)).await, ["m.mov"]);
    h.svc.remove_proxy_link(Request::new(RemoveProxyLinkRequest { master_id: master.clone(), proxy_id: proxy.clone() })).await.unwrap();
    assert!(h.db.list_proxies(&master).unwrap().is_empty());
    assert_eq!(names(&h, list_req(10, 0, "name", true)).await.len(), 2);
}

#[tokio::test]
async fn proxy_rpc_argument_validation() {
    let h = Harness::new().await;
    let e = h.svc.set_proxy_of(Request::new(SetProxyOfRequest { proxy_id: String::new(), original_id: "x".into() })).await.unwrap_err();
    assert_eq!(e.code(), Code::InvalidArgument);
    let e = h.svc.remove_proxy_link(Request::new(RemoveProxyLinkRequest { master_id: String::new(), proxy_id: "p".into() })).await.unwrap_err();
    assert_eq!(e.code(), Code::InvalidArgument);
}

#[tokio::test]
async fn notes_roundtrip() {
    let h = seeded().await;
    let id = h.db.list_videos(10, 0).unwrap().0[0].id.clone();
    h.svc.update_video_notes(Request::new(UpdateNotesRequest { video_id: id.clone(), notes: "hello".into() })).await.unwrap();
    assert_eq!(h.db.get_notes(&id).unwrap().as_deref(), Some("hello"));
}

#[tokio::test]
async fn library_location_missing_path_reports_failure() {
    let h = Harness::new().await;
    let r = h.svc.add_library_location(Request::new(AddLocationRequest { path: "/definitely/not/here/xyz".into(), recursive: true })).await.unwrap().into_inner();
    assert!(!r.success);
    assert!(h.db.list_library_locations().unwrap().is_empty());
}

#[tokio::test]
async fn library_location_add_list_remove() {
    let h = Harness::new().await;
    let p = h.dir.path().join("lib");
    std::fs::create_dir_all(&p).unwrap();
    let r = h.svc.add_library_location(Request::new(AddLocationRequest { path: p.to_str().unwrap().into(), recursive: true })).await.unwrap().into_inner();
    assert!(r.success, "{}", r.message);
    let l = h.svc.list_library_locations(Request::new(ListLocationsRequest {})).await.unwrap().into_inner();
    assert_eq!(l.locations.len(), 1);
}

#[tokio::test]
async fn catalog_closed_returns_error_not_panic() {
    let h = seeded().await;
    h.db.clear_path();
    assert!(h.svc.list_videos(Request::new(list_req(10, 0, "name", true))).await.is_err());
}

async fn fetch_still(h: &Harness, id: &str) -> Option<Vec<u8>> {
    use tokio_stream::StreamExt;
    let resp = h
        .svc
        .get_thumbnail(Request::new(GetThumbnailRequest {
            video_id: id.to_string(),
            size: "large".into(),
            max_width: 0,
        }))
        .await
        .ok()?;
    let mut s = resp.into_inner();
    let mut out = Vec::new();
    while let Some(c) = s.next().await {
        out.extend(c.ok()?.data);
    }
    Some(out)
}

#[tokio::test]
async fn get_thumbnail_serves_stale_still_when_regeneration_is_impossible() {
    let h = Harness::new().await;
    let id = h.add_video("a.mov", 1);
    let thumbs = h.dir.path().join("thumbs");
    for s in ["small", "medium", "large"] {
        std::fs::write(thumbs.join(format!("{id}_{s}.jpg")), b"old").unwrap();
    }
    // No version marker: made by the old pipeline. The fake backend can't
    // regenerate (and the row has no duration), so the old bytes are the
    // fallback instead of NOT_FOUND.
    assert_eq!(fetch_still(&h, &id).await.as_deref(), Some(&b"old"[..]));
    // Still stale afterwards (regeneration was attempted, not skipped).
    assert!(!reelvault_core::thumbnails::ThumbnailGenerator::stills_current(&thumbs, &id));
}

#[tokio::test]
async fn update_config_without_open_catalog_errors() {
    let h = Harness::new().await;
    h.db.clear_path();
    let r = h.svc.update_config(Request::new(UpdateConfigRequest::default())).await;
    assert!(r.is_err(), "UpdateConfig must not report success with no catalog");
}
