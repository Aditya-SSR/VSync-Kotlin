package com.minimallabs.vsync.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Field names match the JSON the Rust scraper emits (and the demo dataset). */
val AppJson = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

@Serializable
data class Attendance(
    @SerialName("class_number") val classNumber: String = "",
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_name") val courseName: String = "",
    @SerialName("course_type") val courseType: String = "",
    @SerialName("course_type_code") val courseTypeCode: String = "",
    @SerialName("course_slot") val courseSlot: String = "",
    val faculty: String = "",
    @SerialName("attended_classes") val attendedClasses: String = "",
    @SerialName("total_classes") val totalClasses: String = "",
    @SerialName("attendance_percentage") val attendancePercentage: String = "",
    @SerialName("attendance_between_percentage") val betweenAttendancePercentage: String = "",
    @SerialName("debar_status") val debarStatus: String = "",
    @SerialName("course_id") val courseId: String = "",
)

@Serializable
data class AttendanceDetail(
    val serial: String = "",
    val date: String = "",
    val slot: String = "",
    @SerialName("day_time") val dayTime: String = "",
    val status: String = "",
    val remark: String = "",
)

@Serializable
data class Day(
    @SerialName("start_time") val startTime: String = "",
    @SerialName("end_time") val endTime: String = "",
    @SerialName("course_name") val courseName: String = "",
    val slot: String = "",
    val venue: String = "",
    val faculty: String = "",
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_type") val courseType: String = "",
    val credits: String = "",
)

@Serializable
data class TimetableCourse(
    @SerialName("course_code") val code: String = "",
    @SerialName("course_name") val name: String = "",
    @SerialName("course_type") val kind: String = "",
    @SerialName("type_code") val typeCode: String = "",
    val credits: String = "",
)

@Serializable
data class Timetable(
    @SerialName("Monday") val monday: List<Day> = emptyList(),
    @SerialName("Tuesday") val tuesday: List<Day> = emptyList(),
    @SerialName("Wednesday") val wednesday: List<Day> = emptyList(),
    @SerialName("Thursday") val thursday: List<Day> = emptyList(),
    @SerialName("Friday") val friday: List<Day> = emptyList(),
    @SerialName("Saturday") val saturday: List<Day> = emptyList(),
    @SerialName("Sunday") val sunday: List<Day> = emptyList(),
    /** VTOP's course table; null on a timetable saved without one. */
    val courses: List<TimetableCourse>? = null,
) {
    /** Index 1 = Monday ... 7 = Sunday (java.time DayOfWeek values). */
    fun forWeekday(isoDay: Int): List<Day> = when (isoDay) {
        1 -> monday; 2 -> tuesday; 3 -> wednesday; 4 -> thursday
        5 -> friday; 6 -> saturday; else -> sunday
    }

    val isEmpty: Boolean get() = (1..7).all { forWeekday(it).isEmpty() }
}

@Serializable
data class ExamSubject(
    @SerialName("serial_number") val serialNumber: String = "",
    val slot: String = "",
    @SerialName("course_name") val courseName: String = "",
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_type") val courseType: String = "",
    @SerialName("course_id") val courseId: String = "",
    @SerialName("exam_date") val examDate: String = "",
    @SerialName("exam_session") val examSession: String = "",
    @SerialName("reporting_time") val reportingTime: String = "",
    @SerialName("exam_time") val examTime: String = "",
    val venue: String = "",
    @SerialName("seat_location") val seatLocation: String = "",
    @SerialName("seat_number") val seatNumber: String = "",
)

@Serializable
data class ExamSchedule(
    val subjects: List<ExamSubject> = emptyList(),
    @SerialName("exam_type") val examType: String = "",
)

@Serializable
data class MarkDetail(
    @SerialName("serial_number") val serialNumber: String = "",
    @SerialName("mark_title") val markTitle: String = "",
    @SerialName("max_mark") val maxMark: String = "",
    val weightage: String = "",
    val status: String = "",
    @SerialName("scored_mark") val scoredMark: String = "",
    @SerialName("weightage_mark") val weightageMark: String = "",
    val remark: String = "",
)

@Serializable
data class Mark(
    @SerialName("serial_number") val serialNumber: String = "",
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_title") val courseTitle: String = "",
    @SerialName("course_type") val courseType: String = "",
    val faculty: String = "",
    val slot: String = "",
    val details: List<MarkDetail> = emptyList(),
)

@Serializable
data class GradeCourse(
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_title") val courseTitle: String = "",
    @SerialName("course_type") val courseType: String = "",
    val credits: String = "",
    val grade: String = "",
    @SerialName("exam_month") val examMonth: String = "",
    @SerialName("course_distribution") val courseDistribution: String = "",
)

@Serializable
data class GradeHistory(
    @SerialName("credits_registered") val creditsRegistered: String = "",
    @SerialName("credits_earned") val creditsEarned: String = "",
    val cgpa: String = "",
    val courses: List<GradeCourse> = emptyList(),
)

@Serializable
data class MentorDetails(
    @SerialName("faculty_id") val facultyId: String = "",
    @SerialName("faculty_name") val facultyName: String = "",
    @SerialName("faculty_designation") val facultyDesignation: String = "",
    val school: String = "",
    val cabin: String = "",
    @SerialName("faculty_department") val facultyDepartment: String = "",
    @SerialName("faculty_email") val facultyEmail: String = "",
    @SerialName("faculty_intercom") val facultyIntercom: String = "",
    @SerialName("faculty_mobile_number") val facultyMobileNumber: String = "",
)

@Serializable
data class Profile(
    @SerialName("application_number") val applicationNumber: String = "",
    @SerialName("registration_number") val registrationNumber: String = "",
    @SerialName("student_name") val studentName: String = "",
    val dob: String = "",
    val gender: String = "",
    @SerialName("blood_group") val bloodGroup: String = "",
    val email: String = "",
    @SerialName("grade_history") val gradeHistory: GradeHistory = GradeHistory(),
    @SerialName("mentor_details") val mentorDetails: MentorDetails = MentorDetails(),
)

/** Everything `fetch_all_data` returns; the app's saved copy of the student. */
@Serializable
data class UserData(
    val profile: Profile = Profile(),
    val attendance: List<Attendance> = emptyList(),
    val timetable: Timetable = Timetable(),
    @SerialName("exam_schedule") val examSchedule: List<ExamSchedule> = emptyList(),
    val marks: List<Mark> = emptyList(),
)

@Serializable
data class Credentials(
    val registrationNumber: String,
    val password: String,
    val semSubId: String,
    val wifiUsername: String? = null,
    val wifiPassword: String? = null,
)

@Serializable
data class SemesterInfo(val id: String = "", val name: String = "")

@Serializable
data class SemesterData(val semesters: List<SemesterInfo> = emptyList(), @SerialName("update_time") val updateTime: Long = 0)

@Serializable
data class SemesterCache(
    val semesterId: String,
    val semesterName: String,
    val isSelected: Boolean = false,
    val updatedAt: Long = 0,
)

@Serializable
data class Biometric(
    val serial: String = "",
    val date: String = "",
    val day: String = "",
    @SerialName("in_time") val inTime: String = "",
    @SerialName("out_time") val outTime: String = "",
    val duration: String = "",
    val location: String = "",
)

@Serializable
data class FacultyBrief(
    @SerialName("faculty_name") val facultyName: String = "",
    val designation: String = "",
    @SerialName("school_or_centre") val schoolOrCentre: String = "",
    @SerialName("emp_id") val empId: String = "",
)

@Serializable
data class OfficeHour(val day: String = "", val timings: String = "")

@Serializable
data class FacultyDetails(
    val name: String = "",
    val designation: String = "",
    val department: String = "",
    @SerialName("school_centre") val schoolCentre: String = "",
    val email: String = "",
    @SerialName("cabin_number") val cabinNumber: String = "",
    @SerialName("office_hours") val officeHours: List<OfficeHour> = emptyList(),
)

@Serializable
data class GeneralOuting(
    val serial: String = "",
    @SerialName("registration_number") val registrationNumber: String = "",
    @SerialName("place_of_visit") val placeOfVisit: String = "",
    @SerialName("purpose_of_visit") val purposeOfVisit: String = "",
    @SerialName("from_date") val fromDate: String = "",
    @SerialName("from_time") val fromTime: String = "",
    @SerialName("to_date") val toDate: String = "",
    @SerialName("to_time") val toTime: String = "",
    val status: String = "",
    @SerialName("can_download") val canDownload: Boolean = false,
    @SerialName("leave_id") val leaveId: String = "",
)

@Serializable
data class WeekendOuting(
    val serial: String = "",
    @SerialName("registration_number") val registrationNumber: String = "",
    @SerialName("hostel_block") val hostelBlock: String = "",
    @SerialName("room_number") val roomNumber: String = "",
    @SerialName("place_of_visit") val placeOfVisit: String = "",
    @SerialName("purpose_of_visit") val purposeOfVisit: String = "",
    val time: String = "",
    @SerialName("contact_number") val contactNumber: String = "",
    @SerialName("parent_contact_number") val parentContactNumber: String = "",
    val date: String = "",
    @SerialName("booking_id") val bookingId: String = "",
    val status: String = "",
    @SerialName("can_download") val canDownload: Boolean = false,
)

@Serializable
data class PendingPayment(
    @SerialName("s_no") val sNo: String = "",
    val fprefno: String = "",
    @SerialName("fees_heads") val feesHeads: String = "",
    @SerialName("end_date") val endDate: String = "",
    val amount: String = "",
    val fine: String = "",
    @SerialName("total_amount") val totalAmount: String = "",
    @SerialName("payment_status") val paymentStatus: String = "",
)

@Serializable
data class PaymentReceipt(
    @SerialName("receipt_number") val receiptNumber: String = "",
    val date: String = "",
    val amount: String = "",
    @SerialName("campus_code") val campusCode: String = "",
    @SerialName("payment_status") val paymentStatus: String = "",
    @SerialName("receipt_no") val receiptNo: String = "",
)

@Serializable
data class GradeViewCourse(
    @SerialName("serial_number") val serialNumber: String = "",
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_title") val courseTitle: String = "",
    @SerialName("course_type") val courseType: String = "",
    @SerialName("grading_type") val gradingType: String = "",
    @SerialName("grand_total") val grandTotal: String = "",
    val grade: String = "",
    @SerialName("course_id") val courseId: String = "",
)

// ---- Course page ----

@Serializable
data class CourseOption(
    val value: String = "",
    val label: String = "",
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_title") val courseTitle: String = "",
    @SerialName("course_type") val courseType: String = "",
)

@Serializable
data class CoursesResponse(val courses: List<CourseOption> = emptyList())

@Serializable
data class SlotOption(val value: String = "", val label: String = "")

@Serializable
data class CourseClassEntry(
    @SerialName("sl_no") val slNo: Int = 0,
    @SerialName("class_group") val classGroup: String = "",
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_title") val courseTitle: String = "",
    @SerialName("course_type") val courseType: String = "",
    @SerialName("class_id") val classId: String = "",
    val slot: String = "",
    val faculty: String = "",
    @SerialName("semester_id") val semesterId: String = "",
    @SerialName("erp_id") val erpId: String = "",
)

@Serializable
data class SlotsResponse(
    val slots: List<SlotOption> = emptyList(),
    @SerialName("class_entries") val classEntries: List<CourseClassEntry> = emptyList(),
)

@Serializable
data class ReferenceMaterial(val label: String = "", @SerialName("download_path") val downloadPath: String = "")

@Serializable
data class LectureEntry(
    @SerialName("sl_no") val slNo: Int = 0,
    val date: String = "",
    @SerialName("formatted_date") val formattedDate: String = "",
    val day: String = "",
    val topic: String = "",
    @SerialName("reference_materials") val referenceMaterials: List<ReferenceMaterial> = emptyList(),
)

@Serializable
data class CourseInfo(
    @SerialName("class_group") val classGroup: String = "",
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_title") val courseTitle: String = "",
    @SerialName("course_type") val courseType: String = "",
    @SerialName("class_id") val classId: String = "",
    val slot: String = "",
    val faculty: String = "",
    @SerialName("course_id") val courseId: String = "",
)

@Serializable
data class CoursePageDetail(
    @SerialName("course_info") val courseInfo: CourseInfo = CourseInfo(),
    @SerialName("semester_id") val semesterId: String = "",
    @SerialName("download_all_path") val downloadAllPath: String? = null,
    @SerialName("download_general_materials_path") val downloadGeneralMaterialsPath: String? = null,
    @SerialName("syllabus_download_path") val syllabusDownloadPath: String? = null,
    @SerialName("course_plan_download_path") val coursePlanDownloadPath: String? = null,
    val lectures: List<LectureEntry> = emptyList(),
)

// ---- Digital assignments ----

@Serializable
data class AssignmentDetail(
    @SerialName("serial_number") val serialNumber: String = "",
    @SerialName("assignment_title") val assignmentTitle: String = "",
    @SerialName("max_assignment_mark") val maxAssignmentMark: String = "",
    @SerialName("assignment_weightage_mark") val assignmentWeightageMark: String = "",
    @SerialName("due_date") val dueDate: String = "",
    @SerialName("can_qp_download") val canQpDownload: Boolean = false,
    @SerialName("qp_download_url") val qpDownloadUrl: String = "",
    @SerialName("submission_status") val submissionStatus: String = "",
    @SerialName("can_update") val canUpdate: Boolean = false,
    val mcode: String = "",
    @SerialName("can_da_download") val canDaDownload: Boolean = false,
    @SerialName("da_download_url") val daDownloadUrl: String = "",
)

@Serializable
data class DigitalAssignment(
    @SerialName("serial_number") val serialNumber: String = "",
    @SerialName("class_id") val classId: String = "",
    @SerialName("course_code") val courseCode: String = "",
    @SerialName("course_title") val courseTitle: String = "",
    @SerialName("course_type") val courseType: String = "",
    val faculty: String = "",
    val details: List<AssignmentDetail> = emptyList(),
)

// ---- Academic calendar (raw scraper shape) ----

@Serializable
data class ClassGroup(val id: String = "", val name: String = "")

@Serializable
data class CalendarMonthRef(val label: String = "", @SerialName("cal_date") val calDate: String = "")

@Serializable
data class RawCalendarEvent(val description: String = "", val label: String = "")

@Serializable
data class RawCalendarDay(
    val date: String = "",
    val day: Int = 0,
    val weekday: String = "",
    val events: List<RawCalendarEvent> = emptyList(),
)

@Serializable
data class AcademicCalendar(
    @SerialName("semester_id") val semesterId: String = "",
    @SerialName("class_group_id") val classGroupId: String = "",
    val months: List<CalendarMonthRef> = emptyList(),
    val days: List<RawCalendarDay> = emptyList(),
)

// ---- Capstone ----

@Serializable
data class CapstonePunch(
    val serial: String = "",
    val date: String = "",
    val day: String = "",
    @SerialName("day_type") val dayType: String = "",
    val status: String = "",
    @SerialName("punch_time") val punchTime: String = "",
)

@Serializable
data class CapstoneAttendance(
    val title: String = "",
    @SerialName("guide_evaluation_status") val guideEvaluationStatus: String = "",
    @SerialName("date_of_registration") val dateOfRegistration: String = "",
    val present: String = "",
    @SerialName("on_duty") val onDuty: String = "",
    val absent: String = "",
    val percentage: String = "",
    val punches: List<CapstonePunch> = emptyList(),
)

@Serializable
data class AttendanceWithCapstone(
    val records: List<Attendance> = emptyList(),
    val capstone: CapstoneAttendance? = null,
)
