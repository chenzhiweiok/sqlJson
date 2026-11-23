create table sys_user (
  id bigint auto_increment primary key,
  user_name varchar(64) not null,
  email varchar(128),
  dept varchar(64),
  status varchar(16) default 'ACTIVE',
  password_hash varchar(128),
  role varchar(16) default 'MEMBER',
  deleted int not null default 0,
  created_at timestamp,
  updated_at timestamp,
  created_by varchar(64),
  updated_by varchar(64)
);

create table sys_relation (
  id bigint auto_increment primary key,
  user_id bigint not null,
  target_id bigint not null,
  rel_type varchar(16) not null,
  created_at timestamp
);
