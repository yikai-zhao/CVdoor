package com.cvdoor.app.flow

/** 各行業的 ATS 關鍵詞預覽庫（硬編碼，Demo 頁使用） */
data class IndustryDef(
    val id: String,
    val displayName: String,
    val sampleKeywords: List<String>,        // first 6 shown on page 04
    val fullKeywords: List<String>,          // shown in demo preview page 06
    val suggestedRoles: List<String>,
    /** Field labels shown in DataSupplementScreen to collect real quantitative data */
    val dataFields: List<String> = emptyList()
)

object IndustryData {

    val regions = listOf("香港", "加拿大", "英国", "美国", "新加坡", "澳大利亚")

    val industries: List<IndustryDef> = listOf(
        IndustryDef(
            id = "education",
            displayName = "教育 / 幼儿教育",
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
            suggestedRoles = listOf("幼稚园教学助理", "课程设计员", "教学支援人员", "幼儿教育教师"),
            dataFields = listOf("每日照顾儿童数量", "每周课堂活动次数", "准备教学材料数量", "家长沟通频率")
        ),
        IndustryDef(
            id = "data_it",
            displayName = "数据 / IT",
            sampleKeywords = listOf(
                "SQL", "Python", "Data Pipeline", "Dashboard", "Data Modeling", "Cloud"
            ),
            fullKeywords = listOf(
                "SQL", "Python", "ETL", "Data Pipeline", "Data Modeling",
                "Dashboard", "Power BI", "Tableau", "Cloud (AWS/GCP/Azure)",
                "Apache Spark", "dbt", "Data Warehouse", "API Integration",
                "Git", "Agile", "Data Governance", "Machine Learning"
            ),
            suggestedRoles = listOf("Data Analyst", "Data Engineer", "Business Intelligence", "Data Scientist"),
            dataFields = listOf("日均处理数据量（GB）", "开发或维护项目数量", "API集成数量", "团队协作人数")
        ),
        IndustryDef(
            id = "marketing",
            displayName = "市场营销",
            sampleKeywords = listOf(
                "Social Media", "SEO", "Campaign", "Content Writing", "Analytics", "Brand"
            ),
            fullKeywords = listOf(
                "Social Media Management", "SEO / SEM", "Content Strategy",
                "Email Marketing", "Google Analytics", "Campaign Management",
                "Brand Awareness", "Digital Advertising", "CRM", "KPI Tracking",
                "A/B Testing", "Influencer Marketing", "Copywriting", "Market Research"
            ),
            suggestedRoles = listOf("Marketing Coordinator", "Social Media Manager", "Content Creator", "SEO Specialist"),
            dataFields = listOf("管理社媒账号数量", "内容月均发布量（篇）", "粉丝增长率（%）", "管理广告预算（万元）")
        ),
        IndustryDef(
            id = "customer_service",
            displayName = "客户服务",
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
            suggestedRoles = listOf("Customer Service Representative", "Support Specialist", "Account Manager", "Customer Success"),
            dataFields = listOf("日均处理工单数", "客户满意度评分（%）", "平均解决时长（分钟）", "月均服务客户数量")
        ),
        IndustryDef(
            id = "finance",
            displayName = "金融 / 会计",
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
            suggestedRoles = listOf("Accountant", "Financial Analyst", "Audit Associate", "Treasury Officer"),
            dataFields = listOf("管理账目金额（万元）", "每月处理凭证数量", "参与审计项目数量", "成本节约金额（万元）")
        ),
        IndustryDef(
            id = "hr",
            displayName = "人力资源",
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
            suggestedRoles = listOf("HR Coordinator", "Talent Acquisition Specialist", "HR Business Partner", "Recruiter"),
            dataFields = listOf("成功招聘岗位数量", "完成入职员工数量", "组织培训项目数量", "员工保留率提升（%）")
        ),
        IndustryDef(
            id = "retail_sales",
            displayName = "零售 / 销售",
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
            suggestedRoles = listOf("Sales Associate", "Retail Manager", "Account Executive", "Business Development"),
            dataFields = listOf("月均销售额（万元）", "管理SKU或产品种类数量", "团队人数", "达成销售目标率（%）")
        ),
        IndustryDef(
            id = "healthcare",
            displayName = "医疗 / 护理",
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
            suggestedRoles = listOf("Registered Nurse", "Medical Assistant", "Healthcare Administrator", "Care Coordinator"),
            dataFields = listOf("日均护理患者数量", "执行护理操作数量", "患者满意度评分（%）", "协作科室或团队数量")
        )
    )

    fun findById(id: String) = industries.find { it.id == id }
    fun findByName(name: String) = industries.find { it.displayName == name }
}
