//! JNI bridge for the Kotlin app.
//!
//! One generic entry point (`call`) dispatches by method name to the SAME
//! public functions the Flutter bridge uses (`lib_vtop::api::vtop_get_client`).
//! Nothing in the scraper is changed.
//!
//! Reply format (a byte array):
//!   byte 0 = 0 -> the rest is UTF-8 text (usually JSON)
//!   byte 0 = 1 -> the rest is raw bytes (a PDF, a file)
//!   byte 0 = 2 -> the rest is UTF-8 JSON `{"type","detail","message","debug"}`

use std::collections::HashMap;
use std::panic::{catch_unwind, AssertUnwindSafe};
use std::sync::atomic::{AtomicI64, Ordering};
use std::sync::{Arc, Mutex, OnceLock};

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jbyteArray, jlong};
use jni::JNIEnv;
use lib_vtop::api::vtop::vtop_client::{VtopClient, VtopError};
use lib_vtop::api::vtop_get_client as api;
use serde_json::json;
use tokio::runtime::Runtime;

type SharedClient = Arc<tokio::sync::Mutex<VtopClient>>;

fn runtime() -> &'static Runtime {
    static RT: OnceLock<Runtime> = OnceLock::new();
    RT.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .enable_all()
            .worker_threads(2)
            .build()
            .expect("tokio runtime")
    })
}

fn clients() -> &'static Mutex<HashMap<i64, SharedClient>> {
    static CLIENTS: OnceLock<Mutex<HashMap<i64, SharedClient>>> = OnceLock::new();
    CLIENTS.get_or_init(|| Mutex::new(HashMap::new()))
}

static NEXT_HANDLE: AtomicI64 = AtomicI64::new(1);

enum Out {
    Text(String),
    Bytes(Vec<u8>),
}

fn text(r: Result<String, VtopError>) -> Result<Out, VtopError> {
    r.map(Out::Text)
}

fn bytes(r: Result<Vec<u8>, VtopError>) -> Result<Out, VtopError> {
    r.map(Out::Bytes)
}

fn unit(r: Result<(), VtopError>) -> Result<Out, VtopError> {
    r.map(|_| Out::Text(String::new()))
}

fn to_json<T: serde::Serialize>(r: Result<T, VtopError>) -> Result<Out, VtopError> {
    let v = r?;
    serde_json::to_string(&v)
        .map(Out::Text)
        .map_err(|e| VtopError::ParseError(format!("Failed to serialize: {}", e)))
}

async fn dispatch(
    c: &mut VtopClient,
    method: &str,
    a: &[String],
    payload: Vec<u8>,
) -> Result<Out, VtopError> {
    let s = |i: usize| -> String { a.get(i).cloned().unwrap_or_default() };
    match method {
        "login" => unit(api::vtop_client_login(c).await),
        "login_otp" => unit(api::handle_login_otp(c, s(0)).await),
        "login_otp_resend" => unit(api::handle_login_otp_resend(c).await),
        "semesters" => to_json(api::fetch_semesters(c).await),
        "all_data" => text(api::fetch_all_data(c, s(0)).await),
        "attendance" => text(api::fetch_attendance(c, s(0)).await),
        "attendance_with_capstone" => text(api::fetch_attendance_with_capstone(c, s(0)).await),
        "academic_calendar" => text(api::fetch_academic_calendar(c, s(0), s(1)).await),
        "calendar_class_groups" => text(api::fetch_calendar_class_groups(c, s(0)).await),
        "attendance_detail" => text(api::fetch_attendance_detail(c, s(0), s(1), s(2)).await),
        "timetable" => text(api::fetch_timetable(c, s(0)).await),
        "marks" => text(api::fetch_marks(c, s(0)).await),
        "exam_schedule" => text(api::fetch_exam_shedule(c, s(0)).await),
        "cookies" => bytes(api::fetch_cookies(c).await),
        "csrf" => Ok(Out::Text(api::fetch_csrf_token(c).unwrap_or_default())),
        "username" => Ok(Out::Text(api::fetch_username(c))),
        "user_agent" => Ok(Out::Text(api::fetch_user_agent(c))),
        "is_auth" => Ok(Out::Text(api::fetch_is_auth(c).await.to_string())),
        "biometric" => text(api::fetch_biometric_data(c, s(0)).await),
        "faculty_search" => to_json(api::fetch_faculty_search(c, s(0)).await),
        "faculty_data" => to_json(api::fetch_faculty_data(c, s(0)).await),
        "all_faculty" => text(api::fetch_all_faculty(c).await),
        "weekend_outing_reports" => text(api::fetch_weekend_outing_reports(c).await),
        "weekend_outing_pdf" => bytes(api::fetch_weekend_outing_pdf(c, s(0)).await),
        "submit_general_outing" => {
            text(api::submit_general_outing_form(c, s(0), s(1), s(2), s(3), s(4), s(5)).await)
        }
        "submit_weekend_outing" => {
            text(api::submit_weekend_outing_form(c, s(0), s(1), s(2), s(3), s(4)).await)
        }
        "delete_general_outing" => text(api::delete_general_outing(c, s(0)).await),
        "general_outing_reports" => text(api::fetch_general_outing_reports(c).await),
        "general_outing_pdf" => bytes(api::fetch_general_outing_pdf(c, s(0)).await),
        "delete_weekend_outing" => text(api::delete_weekend_outing(c, s(0)).await),
        "student_profile" => text(api::fetch_student_profile(c).await),
        "grade_history" => to_json(api::fetch_grade_history(c).await),
        "grade_view" => text(api::fetch_grade_view(c, s(0)).await),
        "grade_view_detail" => text(api::fetch_grade_view_detail(c, s(0), s(1)).await),
        "pending_payments" => text(api::fetch_pending_payments(c).await),
        "payment_receipts" => text(api::fetch_payment_receipts(c).await),
        "payment_receipt_download" => {
            text(api::student_payment_receipt_download(c, s(0), s(1)).await)
        }
        "init_course_page" => text(api::init_course_page(c).await),
        "courses_for_course_page" => text(api::fetch_courses_for_course_page(c, s(0)).await),
        "slots_for_course_page" => text(api::fetch_slots_for_course_page(c, s(0), s(1)).await),
        "course_detail" => text(api::fetch_course_detail(c, s(0), s(1), s(2)).await),
        "download_course_material" => bytes(api::download_course_material(c, s(0)).await),
        "download_all_course_materials" => {
            bytes(api::download_all_course_materials(c, s(0)).await)
        }
        "download_course_syllabus" => bytes(api::download_course_syllabus(c, s(0), s(1)).await),
        "download_course_plan_excel" => {
            bytes(api::download_course_plan_excel(c, s(0), s(1)).await)
        }
        "digital_assignments" => text(api::fetch_digital_assignments(c, s(0)).await),
        "upload_digital_assignment" => {
            text(api::upload_digital_assignment(c, s(0), s(1), s(2), payload).await)
        }
        "upload_digital_assignment_otp" => {
            text(api::upload_digital_assignment_with_otp(c, s(0)).await)
        }
        "download_digital_assignment" => bytes(api::download_digital_assignment(c, s(0)).await),
        other => Err(VtopError::ConfigurationError(format!(
            "Unknown bridge method: {}",
            other
        ))),
    }
}

fn error_reply(kind: &str, detail: &str, message: &str, debug: &str) -> Vec<u8> {
    let mut out = vec![2u8];
    out.extend_from_slice(
        json!({"type": kind, "detail": detail, "message": message, "debug": debug})
            .to_string()
            .as_bytes(),
    );
    out
}

fn encode(result: Result<Out, VtopError>) -> Vec<u8> {
    match result {
        Ok(Out::Text(t)) => {
            let mut out = Vec::with_capacity(t.len() + 1);
            out.push(0u8);
            out.extend_from_slice(t.as_bytes());
            out
        }
        Ok(Out::Bytes(b)) => {
            let mut out = Vec::with_capacity(b.len() + 1);
            out.push(1u8);
            out.extend_from_slice(&b);
            out
        }
        Err(e) => {
            let detail = match &e {
                VtopError::AuthenticationFailed(m)
                | VtopError::ParseError(m)
                | VtopError::ConfigurationError(m) => m.clone(),
                _ => String::new(),
            };
            error_reply(&e.error_type(), &detail, &e.message(), &e.debug_message())
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_minimallabs_vsync_bridge_Native_newClient<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    username: JString<'l>,
    password: JString<'l>,
    user_agent: JString<'l>,
) -> jlong {
    let get = |env: &mut JNIEnv<'l>, s: &JString<'l>| -> String {
        env.get_string(s).map(|v| v.into()).unwrap_or_default()
    };
    let username = get(&mut env, &username);
    let password = get(&mut env, &password);
    let user_agent = get(&mut env, &user_agent);

    let made = catch_unwind(AssertUnwindSafe(|| {
        // Built inside the runtime: reqwest wants a reactor around.
        let _guard = runtime().enter();
        api::get_vtop_client(username, password, user_agent)
    }));
    match made {
        Ok(client) => {
            let handle = NEXT_HANDLE.fetch_add(1, Ordering::SeqCst);
            clients()
                .lock()
                .unwrap()
                .insert(handle, Arc::new(tokio::sync::Mutex::new(client)));
            handle
        }
        Err(_) => 0,
    }
}

#[no_mangle]
pub extern "system" fn Java_com_minimallabs_vsync_bridge_Native_freeClient<'l>(
    _env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
) {
    if let Ok(mut map) = clients().lock() {
        map.remove(&handle);
    }
}

#[no_mangle]
pub extern "system" fn Java_com_minimallabs_vsync_bridge_Native_call<'l>(
    mut env: JNIEnv<'l>,
    _class: JClass<'l>,
    handle: jlong,
    method: JString<'l>,
    args_json: JString<'l>,
    payload: JByteArray<'l>,
) -> jbyteArray {
    let method: String = env.get_string(&method).map(|v| v.into()).unwrap_or_default();
    let args_json: String = env
        .get_string(&args_json)
        .map(|v| v.into())
        .unwrap_or_default();
    let payload: Vec<u8> = if payload.is_null() {
        Vec::new()
    } else {
        env.convert_byte_array(&payload).unwrap_or_default()
    };
    let args: Vec<String> = serde_json::from_str(&args_json).unwrap_or_default();

    let client = clients().lock().ok().and_then(|m| m.get(&handle).cloned());

    let reply = match client {
        None => error_reply(
            "ConfigurationError",
            "No such client",
            "App configuration error. Please restart the app.",
            "Bridge: unknown client handle",
        ),
        Some(client) => {
            let run = catch_unwind(AssertUnwindSafe(|| {
                runtime().block_on(async {
                    let mut guard = client.lock().await;
                    dispatch(&mut guard, &method, &args, payload).await
                })
            }));
            match run {
                Ok(result) => encode(result),
                Err(_) => error_reply(
                    "ParseError",
                    "The scraper crashed while handling this request",
                    "Unable to process server response. Please try again.",
                    "Bridge: panic in scraper",
                ),
            }
        }
    };

    match env.byte_array_from_slice(&reply) {
        Ok(arr) => arr.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}
