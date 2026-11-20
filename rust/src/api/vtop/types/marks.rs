use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MarksRecordEach {
    pub serial_number: String,
    pub mark_title: String,
    pub max_mark: String,
    pub weightage: String,
    pub status: String,
    pub scored_mark: String,
    pub weightage_mark: String,
    pub remark: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Marks {
    pub serial_number: String,
    pub course_code: String,
    pub course_title: String,
    pub course_type: String,
    pub faculty: String,
    pub slot: String,
    pub details: Vec<MarksRecordEach>,
}
