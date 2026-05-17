package com.cvdoor.app.flow

/** 各行業的 ATS 關鍵詞預覽庫（硬編碼，Demo 頁使用） */
data class IndustryDef(
    val id: String,
    val displayName: String,
    val sampleKeywords: List<String>,        // first 6 shown on page 04
    val fullKeywords: List<String>,          // shown in demo preview page 06
    val suggestedRoles: List<String>
)

object IndustryData {

    val regions = listOf("香港", "加拿大", "英國", "美國", "新加坡", "澳大利亞")

    val industries: List<IndustryDef> = listOf(
        IndustryDef(
            id = "education",
            displayName = "教育 / 幼兒教育",
            sampleKeywords = listOf(
                "Classroom Support", "Child Development", "Behavior Management",
                "Teaching Materials", "Communication Skills", "Team Collaboration"
            ),
            fullKeywords = listOf(
                "Classroom Management", "Child Development", "Behavior Management",
                "Teaching Materials", "Lesson Planning", "Parent Communication",
                "Learning Assessment", "Differentiated Instruction", "IEP Support",
                "English-Chinese Bilingual", "Phonics", "Circle Time",
                "Emotional Intelligence", "Safeguarding", "EYFS Framework"
            ),
            suggestedRoles = listOf("幼稚園教學助理", "課程設計員", "教學支援人員", "幼兒教育教師")
        ),
        IndustryDef(
            id = "data_it",
            displayName = "數據 / IT",
            sampleKeywords = listOf(
                "SQL", "Python", "Data Pipeline", "Dashboard", "Data Modeling", "Cloud"
            ),
            fullKeywords = listOf(
                "SQL", "Python", "ETL", "Data Pipeline", "Data Modeling",
                "Dashboard", "Power BI", "Tableau", "Cloud (AWS/GCP/Azure)",
                "Apache Spark", "dbt", "Data Warehouse", "API Integration",
                "Git", "Agile", "Data Governance", "Machine Learning"
            ),
            suggestedRoles = listOf("Data Analyst", "Data Engineer", "Business Intelligence", "Data Scientist")
        ),
        IndustryDef(
            id = "marketing",
            displayName = "市場營銷",
            sampleKeywords = listOf(
                "Social Media", "SEO", "Campaign", "Content Writing", "Analytics", "Brand"
            ),
            fullKeywords = listOf(
                "Social Media Management", "SEO / SEM", "Content Strategy",
                "Email Marketing", "Google Analytics", "Campaign Management",
                "Brand Awareness", "Digital Advertising", "CRM", "KPI Tracking",
                "A/B Testing", "Influencer Marketing", "Copywriting", "Market Research"
            ),
            suggestedRoles = listOf("Marketing Coordinator", "Social Media Manager", "Content Creator", "SEO Specialist")
        ),
        IndustryDef(
            id = "customer_service",
            displayName = "客戶服務",
            sampleKeywords = listOf(
                "Customer Support", "Issue Resolution", "CRM", "Communication", "Service Quality", "SLA"
            ),
            fullKeywords = listOf(
                "Customer Support", "Issue Resolution", "CRM Systems",
                "Complaint Handling", "SLA Compliance", "Live Chat",
                "Ticketing System", "Escalation Management", "Customer Satisfaction",
                "Upselling", "After-sales Support", "Product Knowledge",
                "Multichannel Support", "Net Promoter Score"
            ),
            suggestedRoles = listOf("Customer Service Representative", "Support Specialist", "Account Manager", "Customer Success")
        ),
        IndustryDef(
            id = "finance",
            displayName = "金融 / 會計",
            sampleKeywords = listOf(
                "Financial Reporting", "Budgeting", "Forecasting", "Excel", "GAAP", "Reconciliation"
            ),
            fullKeywords = listOf(
                "Financial Reporting", "Budgeting & Forecasting", "Reconciliation",
                "Excel / VBA", "GAAP / IFRS", "Accounts Payable/Receivable",
                "Audit Support", "Tax Compliance", "ERP Systems (SAP/Oracle)",
                "Cash Flow Management", "Financial Modelling", "Risk Management",
                "Investment Analysis", "Cost Analysis"
            ),
            suggestedRoles = listOf("Accountant", "Financial Analyst", "Audit Associate", "Treasury Officer")
        ),
        IndustryDef(
            id = "hr",
            displayName = "人力資源",
            sampleKeywords = listOf(
                "Recruitment", "Onboarding", "HR Policies", "Employee Relations", "HRIS", "L&D"
            ),
            fullKeywords = listOf(
                "Recruitment & Selection", "Onboarding", "HR Policies",
                "Employee Relations", "HRIS (Workday/SAP)", "Learning & Development",
                "Performance Management", "Compensation & Benefits",
                "Talent Acquisition", "Job Posting", "Interview Coordination",
                "Compliance", "Labor Law", "Organizational Development"
            ),
            suggestedRoles = listOf("HR Coordinator", "Talent Acquisition Specialist", "HR Business Partner", "Recruiter")
        ),
        IndustryDef(
            id = "retail_sales",
            displayName = "零售 / 銷售",
            sampleKeywords = listOf(
                "Sales Target", "Product Knowledge", "Customer Engagement", "POS", "KPI", "Upselling"
            ),
            fullKeywords = listOf(
                "Sales Target Achievement", "Product Knowledge", "Customer Engagement",
                "POS Systems", "KPI Tracking", "Upselling / Cross-selling",
                "Visual Merchandising", "Inventory Management", "Retail Operations",
                "Brand Ambassador", "Customer Loyalty", "Store Opening/Closing",
                "Sales Reporting", "Team Leadership"
            ),
            suggestedRoles = listOf("Sales Associate", "Retail Manager", "Account Executive", "Business Development")
        ),
        IndustryDef(
            id = "healthcare",
            displayName = "醫療 / 護理",
            sampleKeywords = listOf(
                "Patient Care", "Clinical Documentation", "Medical Terminology", "HIPAA", "EMR", "Triage"
            ),
            fullKeywords = listOf(
                "Patient Care", "Clinical Documentation", "Medical Terminology",
                "HIPAA Compliance", "EMR / EHR Systems", "Triage",
                "Medication Administration", "Vital Signs Monitoring",
                "Patient Education", "Infection Control", "Care Coordination",
                "Interdisciplinary Teams", "CPR / First Aid", "Patient Assessment"
            ),
            suggestedRoles = listOf("Registered Nurse", "Medical Assistant", "Healthcare Administrator", "Care Coordinator")
        )
    )

    fun findById(id: String) = industries.find { it.id == id }
    fun findByName(name: String) = industries.find { it.displayName == name }
}
