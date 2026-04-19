/*
 * Copyright (c) 2010-2011, The MiCode Open Source Community (www.micode.net)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.micode.notes.ui;

import android.content.Context;
import android.database.Cursor;
import android.text.TextUtils;

import net.micode.notes.data.Contact;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.tool.DataUtils;


/**
 * 列表数据模型：从 Cursor 解析笔记/文件夹信息，并提供展示所需的状态判断。
 */
public class NoteItemData {
    static final String [] PROJECTION = new String [] {
        NoteColumns.ID,
        NoteColumns.ALERTED_DATE,
        NoteColumns.BG_COLOR_ID,
        NoteColumns.CREATED_DATE,
        NoteColumns.HAS_ATTACHMENT,
        NoteColumns.MODIFIED_DATE,
        NoteColumns.NOTES_COUNT,
        NoteColumns.PARENT_ID,
        NoteColumns.SNIPPET,
        NoteColumns.TYPE,
        NoteColumns.WIDGET_ID,
        NoteColumns.WIDGET_TYPE,
    };

    private static final int ID_COLUMN                    = 0;
    private static final int ALERTED_DATE_COLUMN          = 1;
    private static final int BG_COLOR_ID_COLUMN           = 2;
    private static final int CREATED_DATE_COLUMN          = 3;
    private static final int HAS_ATTACHMENT_COLUMN        = 4;
    private static final int MODIFIED_DATE_COLUMN         = 5;
    private static final int NOTES_COUNT_COLUMN           = 6;
    private static final int PARENT_ID_COLUMN             = 7;
    private static final int SNIPPET_COLUMN               = 8;
    private static final int TYPE_COLUMN                  = 9;
    private static final int WIDGET_ID_COLUMN             = 10;
    private static final int WIDGET_TYPE_COLUMN           = 11;

    private long mId;
    private long mAlertDate;
    private int mBgColorId;
    private long mCreatedDate;
    private boolean mHasAttachment;
    private long mModifiedDate;
    private int mNotesCount;
    private long mParentId;
    private String mSnippet;
    private int mType;
    private int mWidgetId;
    private int mWidgetType;
    private String mName;
    private String mPhoneNumber;

    private boolean mIsLastItem;
    private boolean mIsFirstItem;
    private boolean mIsOnlyOneItem;
    private boolean mIsOneNoteFollowingFolder;
    private boolean mIsMultiNotesFollowingFolder;

    // 构造：从 Cursor 读取字段并计算列表位置相关状态。
    public NoteItemData(Context context, Cursor cursor) {
        mId = cursor.getLong(ID_COLUMN);
        mAlertDate = cursor.getLong(ALERTED_DATE_COLUMN);
        mBgColorId = cursor.getInt(BG_COLOR_ID_COLUMN);
        mCreatedDate = cursor.getLong(CREATED_DATE_COLUMN);
        mHasAttachment = (cursor.getInt(HAS_ATTACHMENT_COLUMN) > 0) ? true : false;
        mModifiedDate = cursor.getLong(MODIFIED_DATE_COLUMN);
        mNotesCount = cursor.getInt(NOTES_COUNT_COLUMN);
        mParentId = cursor.getLong(PARENT_ID_COLUMN);
        mSnippet = cursor.getString(SNIPPET_COLUMN);
        mSnippet = mSnippet.replace(NoteEditActivity.TAG_CHECKED, "").replace(
                NoteEditActivity.TAG_UNCHECKED, "");
        mType = cursor.getInt(TYPE_COLUMN);
        mWidgetId = cursor.getInt(WIDGET_ID_COLUMN);
        mWidgetType = cursor.getInt(WIDGET_TYPE_COLUMN);

        mPhoneNumber = "";
        if (mParentId == Notes.ID_CALL_RECORD_FOLDER) {
            mPhoneNumber = DataUtils.getCallNumberByNoteId(context.getContentResolver(), mId);
            if (!TextUtils.isEmpty(mPhoneNumber)) {
                mName = Contact.getContact(context, mPhoneNumber);
                if (mName == null) {
                    mName = mPhoneNumber;
                }
            }
        }

        if (mName == null) {
            mName = "";
        }
        checkPostion(cursor);
    }

    // 计算在 Cursor 中的位置状态（首/尾/单个、是否紧跟文件夹等）。
    private void checkPostion(Cursor cursor) {
        mIsLastItem = cursor.isLast() ? true : false;
        mIsFirstItem = cursor.isFirst() ? true : false;
        mIsOnlyOneItem = (cursor.getCount() == 1);
        mIsMultiNotesFollowingFolder = false;
        mIsOneNoteFollowingFolder = false;

        if (mType == Notes.TYPE_NOTE && !mIsFirstItem) {
            int position = cursor.getPosition();
            if (cursor.moveToPrevious()) {
                if (cursor.getInt(TYPE_COLUMN) == Notes.TYPE_FOLDER
                        || cursor.getInt(TYPE_COLUMN) == Notes.TYPE_SYSTEM) {
                    if (cursor.getCount() > (position + 1)) {
                        mIsMultiNotesFollowingFolder = true;
                    } else {
                        mIsOneNoteFollowingFolder = true;
                    }
                }
                if (!cursor.moveToNext()) {
                    throw new IllegalStateException("cursor move to previous but can't move back");
                }
            }
        }
    }

    // 判断：是否只有一个笔记紧跟在文件夹后。
    public boolean isOneFollowingFolder() {
        return mIsOneNoteFollowingFolder;
    }

    // 判断：是否有多个笔记紧跟在文件夹后。
    public boolean isMultiFollowingFolder() {
        return mIsMultiNotesFollowingFolder;
    }

    // 判断：是否为列表最后一项。
    public boolean isLast() {
        return mIsLastItem;
    }

    // 获取通话记录对应的联系人名称（如有）。
    public String getCallName() {
        return mName;
    }

    // 判断：是否为列表第一项。
    public boolean isFirst() {
        return mIsFirstItem;
    }

    // 判断：列表是否只有一项。
    public boolean isSingle() {
        return mIsOnlyOneItem;
    }

    // 获取条目 ID。
    public long getId() {
        return mId;
    }

    // 获取提醒时间戳（毫秒）。
    public long getAlertDate() {
        return mAlertDate;
    }

    // 获取创建时间戳（毫秒）。
    public long getCreatedDate() {
        return mCreatedDate;
    }

    // 判断：是否有附件。
    public boolean hasAttachment() {
        return mHasAttachment;
    }

    // 获取修改时间戳（毫秒）。
    public long getModifiedDate() {
        return mModifiedDate;
    }

    // 获取背景颜色 id。
    public int getBgColorId() {
        return mBgColorId;
    }

    // 获取父文件夹 id。
    public long getParentId() {
        return mParentId;
    }

    // 获取文件夹下笔记数（仅文件夹类型有意义）。
    public int getNotesCount() {
        return mNotesCount;
    }

    // 获取所属文件夹 id（等同 parentId）。
    public long getFolderId () {
        return mParentId;
    }

    // 获取条目类型（笔记/文件夹/系统）。
    public int getType() {
        return mType;
    }

    // 获取挂件类型。
    public int getWidgetType() {
        return mWidgetType;
    }

    // 获取挂件 id。
    public int getWidgetId() {
        return mWidgetId;
    }

    // 获取摘要文本。
    public String getSnippet() {
        return mSnippet;
    }

    // 判断：是否设置了提醒。
    public boolean hasAlert() {
        return (mAlertDate > 0);
    }

    // 判断：是否为带号码的通话记录条目。
    public boolean isCallRecord() {
        return (mParentId == Notes.ID_CALL_RECORD_FOLDER && !TextUtils.isEmpty(mPhoneNumber));
    }

    // 从 Cursor 直接读取条目类型字段。
    public static int getNoteType(Cursor cursor) {
        return cursor.getInt(TYPE_COLUMN);
    }
}
