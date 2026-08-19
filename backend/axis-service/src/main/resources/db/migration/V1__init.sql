-- Axis schema 基线（由 Hibernate 实体模型生成，与 ddl-auto: validate 校验期望一致）。
--
-- 开发期约定：项目未上线、无数据包袱。修改表结构时直接改本文件（加列/改类型/删表皆可），
-- 然后重建本地数据库并重启应用即可，不编写增量迁移脚本：
--   dropdb axis && createdb axis
-- 上线后冻结本文件，之后的变更一律新增 V2__xxx.sql 增量脚本。
--
-- 注意：vector_store 表（pgvector 语义检索）由 PgVectorStore 启动时自建（initializeSchema=true），不在本文件内。

CREATE TABLE ai_config_profile (
    id character varying(36) NOT NULL,
    is_active boolean NOT NULL,
    api_key text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    endpoint character varying(500) NOT NULL,
    model character varying(100) NOT NULL,
    name character varying(100) NOT NULL,
    type character varying(20) NOT NULL DEFAULT 'CHAT',
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT ai_config_profile_pkey PRIMARY KEY (id)
);

CREATE TABLE article_summary_cache (
    link character varying(512) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    detail text NOT NULL,
    headline text NOT NULL,
    model character varying(100) NOT NULL,
    prompt_version character varying(64) NOT NULL,
    tldr text NOT NULL,
    why_it_matters text NOT NULL,
    CONSTRAINT article_summary_cache_pkey PRIMARY KEY (link)
);

CREATE TABLE chat_conversation (
    id character varying(40) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    title character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT chat_conversation_pkey PRIMARY KEY (id)
);

CREATE TABLE chat_long_memory (
    id character varying(30) NOT NULL,
    content text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT chat_long_memory_pkey PRIMARY KEY (id)
);

CREATE TABLE chat_message (
    id character varying(30) NOT NULL,
    content text NOT NULL,
    conversation_id character varying(40) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    role character varying(20) NOT NULL,
    seq integer NOT NULL,
    CONSTRAINT chat_message_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_chat_message_conversation ON chat_message (conversation_id);

CREATE TABLE project (
    id character varying(30) NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    description text,
    name character varying(255) NOT NULL,
    sort_order integer NOT NULL,
    status character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT project_pkey PRIMARY KEY (id),
    CONSTRAINT project_status_check CHECK (status IN ('PLANNING', 'ACTIVE', 'COMPLETED', 'ARCHIVED'))
);

CREATE TABLE issue (
    id character varying(30) NOT NULL,
    attachment text,
    created_at timestamp(6) with time zone NOT NULL,
    description text,
    sort_order integer NOT NULL,
    priority character varying(255) NOT NULL,
    project_id character varying(255),
    status character varying(255) NOT NULL,
    title character varying(255) NOT NULL,
    type character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT issue_pkey PRIMARY KEY (id),
    CONSTRAINT issue_priority_check CHECK (priority IN ('NONE', 'LOW', 'MEDIUM', 'HIGH', 'URGENT')),
    CONSTRAINT issue_status_check CHECK (status IN ('BACKLOG', 'TODO', 'IN_PROGRESS', 'DONE', 'CANCELLED')),
    CONSTRAINT issue_type_check CHECK (type IN ('BUG', 'FEATURE', 'IMPROVEMENT')),
    CONSTRAINT fk_issue_project FOREIGN KEY (project_id) REFERENCES project(id)
);

CREATE TABLE comment (
    id character varying(30) NOT NULL,
    content text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    issue_id character varying(255),
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT comment_pkey PRIMARY KEY (id),
    CONSTRAINT fk_comment_issue FOREIGN KEY (issue_id) REFERENCES issue(id)
);

CREATE TABLE digest_execution_log (
    id character varying(30) NOT NULL,
    article_count integer NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    digest_date date NOT NULL,
    llm_call_count integer,
    status character varying(20) NOT NULL,
    CONSTRAINT digest_execution_log_pkey PRIMARY KEY (id),
    CONSTRAINT uk_digest_date UNIQUE (digest_date),
    CONSTRAINT digest_execution_log_status_check CHECK (status IN ('PENDING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE inbox_item (
    id character varying(30) NOT NULL,
    category character varying(20),
    content text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    digest_date date,
    link character varying(512),
    long_text text,
    published_at timestamp(6) with time zone,
    read_at timestamp(6) with time zone,
    source_name character varying(50),
    status character varying(255) NOT NULL,
    summary text,
    type character varying(255) DEFAULT 'NOTE' NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT inbox_item_pkey PRIMARY KEY (id),
    CONSTRAINT inbox_item_category_check CHECK (category IN ('AI_FRONTIER', 'TECH_INDUSTRY', 'FINANCE_TECH', 'OTHER')),
    CONSTRAINT inbox_item_status_check CHECK (status IN ('TODO', 'DONE')),
    CONSTRAINT inbox_item_type_check CHECK (type IN ('NOTE', 'DIGEST'))
);

CREATE TABLE knowledge_item (
    id character varying(30) NOT NULL,
    content text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    file_path character varying(512),
    mindmap_status character varying(255) NOT NULL,
    progress integer NOT NULL,
    source_url character varying(2048),
    status character varying(255) NOT NULL,
    summary_status character varying(255) NOT NULL,
    title character varying(500) NOT NULL,
    type character varying(255) NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL,
    CONSTRAINT knowledge_item_pkey PRIMARY KEY (id),
    CONSTRAINT knowledge_item_mindmap_status_check CHECK (mindmap_status IN ('PENDING', 'GENERATING', 'DONE', 'FAILED')),
    CONSTRAINT knowledge_item_status_check CHECK (status IN ('UNREAD', 'READING', 'DONE', 'ARCHIVED')),
    CONSTRAINT knowledge_item_summary_status_check CHECK (summary_status IN ('PENDING', 'GENERATING', 'DONE', 'FAILED')),
    CONSTRAINT knowledge_item_type_check CHECK (type IN ('ARTICLE', 'BOOK', 'PODCAST', 'VIDEO', 'TUTORIAL', 'NOTE'))
);

CREATE TABLE knowledge_item_tag (
    item_id character varying(30) NOT NULL,
    tag character varying(50),
    CONSTRAINT fk_knowledge_item_tag_item FOREIGN KEY (item_id) REFERENCES knowledge_item(id)
);

CREATE TABLE knowledge_artifact (
    id character varying(30) NOT NULL,
    content text NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    error character varying(1000),
    kind character varying(255) NOT NULL,
    model character varying(100),
    updated_at timestamp(6) with time zone NOT NULL,
    item_id character varying(30) NOT NULL,
    CONSTRAINT knowledge_artifact_pkey PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_artifact_item_kind UNIQUE (item_id, kind),
    CONSTRAINT knowledge_artifact_kind_check CHECK (kind IN ('SUMMARY', 'MINDMAP')),
    CONSTRAINT fk_knowledge_artifact_item FOREIGN KEY (item_id) REFERENCES knowledge_item(id)
);

CREATE TABLE llm_call_log (
    id character varying(30) NOT NULL,
    completion_tokens integer,
    created_at timestamp(6) with time zone NOT NULL,
    duration_ms bigint NOT NULL,
    error_message character varying(500),
    feature character varying(30) NOT NULL,
    model character varying(100),
    prompt_tokens integer,
    status character varying(10) NOT NULL,
    total_tokens integer,
    CONSTRAINT llm_call_log_pkey PRIMARY KEY (id),
    CONSTRAINT llm_call_log_status_check CHECK (status IN ('SUCCESS', 'ERROR'))
);

CREATE INDEX idx_llm_call_log_created_at ON llm_call_log (created_at);
