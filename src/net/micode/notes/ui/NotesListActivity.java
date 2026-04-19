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

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.appwidget.AppWidgetManager;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.os.AsyncTask;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.ActionMode;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.Display;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnCreateContextMenuListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.AdapterView.OnItemLongClickListener;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import net.micode.notes.R;
import net.micode.notes.data.Notes;
import net.micode.notes.data.Notes.NoteColumns;
import net.micode.notes.gtask.remote.GTaskSyncService;
import net.micode.notes.model.WorkingNote;
import net.micode.notes.tool.BackupUtils;
import net.micode.notes.tool.DataUtils;
import net.micode.notes.tool.ResourceParser;
import net.micode.notes.ui.NotesListAdapter.AppWidgetAttribute;
import net.micode.notes.widget.NoteWidgetProvider_2x;
import net.micode.notes.widget.NoteWidgetProvider_4x;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;

/**
 * 笔记列表页：展示笔记/文件夹列表，支持新建、移动、删除、搜索、导出与同步入口。
 */
public class NotesListActivity extends Activity implements OnClickListener, OnItemLongClickListener {
    private static final int FOLDER_NOTE_LIST_QUERY_TOKEN = 0;

    private static final int FOLDER_LIST_QUERY_TOKEN      = 1;

    private static final int MENU_FOLDER_DELETE = 0;

    private static final int MENU_FOLDER_VIEW = 1;

    private static final int MENU_FOLDER_CHANGE_NAME = 2;

    private static final String PREFERENCE_ADD_INTRODUCTION = "net.micode.notes.introduction";

    private enum ListEditState {
        NOTE_LIST, SUB_FOLDER, CALL_RECORD_FOLDER
    };

    private ListEditState mState;

    private BackgroundQueryHandler mBackgroundQueryHandler;

    private NotesListAdapter mNotesListAdapter;

    private ListView mNotesListView;

    private Button mAddNewNote;

    private boolean mDispatch;

    private int mOriginY;

    private int mDispatchY;

    private TextView mTitleBar;

    private long mCurrentFolderId;

    private ContentResolver mContentResolver;

    private ModeCallback mModeCallBack;

    // 当前多选 ActionMode（用于兜底关闭底部/顶部上下文操作栏）。
    private ActionMode mCurrentActionMode;

    // 便于定位“底部白条”到底是哪一个系统栏位残留：仅打印 split/action_mode 相关视图。
    private static final boolean DEBUG_ACTIONMODE_BAR = true;

    private static final String TAG = "NotesListActivity";

    // ROM 兼容：开启 splitActionBarWhenNarrow 时，部分系统在 ActionMode 结束后底部 split bar 不会自动隐藏。
    private void forceHideActionModeBars() {
        setActionModeBarsVisibility(View.GONE);
    }

    // ROM 兼容：进入多选时强制显示底部/顶部 ActionMode bar（防止上次隐藏后系统未及时恢复）。
    private void forceShowActionModeBars() {
        setActionModeBarsVisibility(View.VISIBLE);
    }

    // 统一设置底部 split/actionMode bar 的可见性（ROM 兼容：仅处理相关栏位，避免误伤内容区导致白屏）。
    private void setActionModeBarsVisibility(int visibility) {
        View decor = getWindow().getDecorView();
        if (decor == null) {
            return;
        }

        // 常见 android 内部 id（仅 split/actionMode 相关）。
        int splitId = getResources().getIdentifier("split_action_bar", "id", "android");
        if (splitId != 0) {
            View split = decor.findViewById(splitId);
            if (split != null) {
                applyActionModeBarVisibility(split, visibility);
            }
        }
        int actionModeId = getResources().getIdentifier("action_mode_bar", "id", "android");
        if (actionModeId != 0) {
            View actionModeBar = decor.findViewById(actionModeId);
            if (actionModeBar != null) {
                applyActionModeBarVisibility(actionModeBar, visibility);
            }
        }

        int actionModeStubId = getResources().getIdentifier("action_mode_bar_stub", "id", "android");
        if (actionModeStubId != 0) {
            View actionModeStub = decor.findViewById(actionModeStubId);
            if (actionModeStub != null) {
                applyActionModeBarVisibility(actionModeStub, visibility);
            }
        }

        // 遍历兜底：仅按资源名匹配 split/actionMode 栏，避免隐藏 action_bar_container 导致白屏。
        traverseAndSetVisibility(decor, visibility);

        if (DEBUG_ACTIONMODE_BAR && visibility == View.GONE) {
            dumpActionModeBarCandidates("setActionModeBarsVisibility(GONE)");
        }
    }

    private void traverseAndSetVisibility(View view, int visibility) {
        if (view == null) {
            return;
        }

        boolean matched = false;
        int id = view.getId();
        if (id != View.NO_ID) {
            try {
                String entry = view.getResources().getResourceEntryName(id);
                if (entry != null) {
                    String name = entry.toLowerCase();
                    if (name.contains("split_action_bar")
                            || name.contains("action_mode")) {
                        matched = true;
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 不再按类名 actionbar/actionmode 匹配：不同 ROM 可能把内容容器也包含在 ActionBar 相关类中，易误伤。
        if (matched) {
            applyActionModeBarVisibility(view, visibility);
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                traverseAndSetVisibility(group.getChildAt(i), visibility);
            }
        }
    }

    private void applyActionModeBarVisibility(View view, int visibility) {
        if (view == null) {
            return;
        }
        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (visibility == View.GONE) {
            view.setVisibility(View.GONE);
            view.setMinimumHeight(0);
            if (lp != null) {
                lp.height = 0;
                view.setLayoutParams(lp);
            }
            view.requestLayout();
        } else {
            if (lp != null && lp.height == 0) {
                lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                view.setLayoutParams(lp);
            }
            view.setVisibility(visibility);
            view.requestLayout();
        }
    }

    private void dumpActionModeBarCandidates(String stage) {
        try {
            View decor = getWindow().getDecorView();
            if (decor == null) {
                return;
            }
            Log.d(TAG, "dumpActionModeBarCandidates: stage=" + stage);
            dumpActionModeBarCandidatesRecursive(decor);
        } catch (Exception e) {
            Log.w(TAG, "dumpActionModeBarCandidates failed: " + e);
        }
    }

    private void dumpActionModeBarCandidatesRecursive(View view) {
        if (view == null) {
            return;
        }

        int id = view.getId();
        String entryName = null;
        if (id != View.NO_ID) {
            try {
                entryName = view.getResources().getResourceEntryName(id);
            } catch (Exception ignored) {
            }
        }
        if (entryName != null) {
            String n = entryName.toLowerCase();
            if (n.contains("split_action_bar") || n.contains("action_mode")) {
                int[] loc = new int[2];
                try {
                    view.getLocationOnScreen(loc);
                } catch (Exception ignored) {
                }
                Log.d(TAG,
                        "  cand: id=" + entryName
                                + " cls=" + view.getClass().getName()
                                + " vis=" + view.getVisibility()
                                + " h=" + view.getHeight()
                                + " y=" + loc[1]);
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                dumpActionModeBarCandidatesRecursive(group.getChildAt(i));
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // ROM 兜底：回到前台时若不在多选，强制隐藏可能残留的 split/actionMode 底栏。
        if (mNotesListAdapter != null && !mNotesListAdapter.isInChoiceMode()
                && mCurrentActionMode == null) {
            mNotesListView.post(new Runnable() {
                @Override
                public void run() {
                    forceHideActionModeBars();
                }
            });
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // ROM 兜底：部分系统在 focus 切换后会把 split bar 设回可见。
        if (hasFocus && mNotesListAdapter != null && !mNotesListAdapter.isInChoiceMode()
                && mCurrentActionMode == null) {
            mNotesListView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    forceHideActionModeBars();
                }
            }, 120);
        }
    }

    public static final int NOTES_LISTVIEW_SCROLL_RATE = 30;

    private NoteItemData mFocusNoteDataItem;

    private static final String NORMAL_SELECTION = NoteColumns.PARENT_ID + "=?";

    private static final String ROOT_FOLDER_SELECTION = "(" + NoteColumns.TYPE + "<>"
            + Notes.TYPE_SYSTEM + " AND " + NoteColumns.PARENT_ID + "=?)" + " OR ("
            + NoteColumns.ID + "=" + Notes.ID_CALL_RECORD_FOLDER + " AND "
            + NoteColumns.NOTES_COUNT + ">0)";

    private final static int REQUEST_CODE_OPEN_NODE = 102;
    private final static int REQUEST_CODE_NEW_NODE  = 103;

    @Override
    // 生命周期：初始化列表与资源，并在首次使用时写入介绍笔记。
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.note_list);
        initResources();

        /**
         * Insert an introduction when user firstly use this application
         */
        setAppInfoFromRawRes();
    }

    @Override
    // 回调：从编辑页返回后按需刷新列表 Cursor。
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (resultCode == RESULT_OK
                && (requestCode == REQUEST_CODE_OPEN_NODE || requestCode == REQUEST_CODE_NEW_NODE)) {
            mNotesListAdapter.changeCursor(null);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    // 首次启动：从 raw/introduction 读取文本并创建一条介绍笔记。
    private void setAppInfoFromRawRes() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
        if (!sp.getBoolean(PREFERENCE_ADD_INTRODUCTION, false)) {
            StringBuilder sb = new StringBuilder();
            InputStream in = null;
            try {
                 in = getResources().openRawResource(R.raw.introduction);
                if (in != null) {
                    InputStreamReader isr = new InputStreamReader(in);
                    BufferedReader br = new BufferedReader(isr);
                    char [] buf = new char[1024];
                    int len = 0;
                    while ((len = br.read(buf)) > 0) {
                        sb.append(buf, 0, len);
                    }
                } else {
                    Log.e(TAG, "Read introduction file error");
                    return;
                }
            } catch (IOException e) {
                e.printStackTrace();
                return;
            } finally {
                if(in != null) {
                    try {
                        in.close();
                    } catch (IOException e) {
                        // TODO Auto-generated catch block
                        e.printStackTrace();
                    }
                }
            }

            WorkingNote note = WorkingNote.createEmptyNote(this, Notes.ID_ROOT_FOLDER,
                    AppWidgetManager.INVALID_APPWIDGET_ID, Notes.TYPE_WIDGET_INVALIDE,
                    ResourceParser.RED);
            note.setWorkingText(sb.toString());
            if (note.saveNote()) {
                sp.edit().putBoolean(PREFERENCE_ADD_INTRODUCTION, true).commit();
            } else {
                Log.e(TAG, "Save introduction note error");
                return;
            }
        }
    }

    @Override
    // 生命周期：启动时异步查询并展示笔记列表。
    protected void onStart() {
        super.onStart();
        startAsyncNotesListQuery();
    }

    // 初始化资源：绑定 ListView/Adapter/按钮/回调，并设置初始状态。
    private void initResources() {
        mContentResolver = this.getContentResolver();
        mBackgroundQueryHandler = new BackgroundQueryHandler(this.getContentResolver());
        mCurrentFolderId = Notes.ID_ROOT_FOLDER;
        mNotesListView = (ListView) findViewById(R.id.notes_list);
        mNotesListView.addFooterView(LayoutInflater.from(this).inflate(R.layout.note_list_footer, null),
                null, false);
        mNotesListView.setOnItemClickListener(new OnListItemClickListener());
        mNotesListView.setOnItemLongClickListener(this);
        mNotesListAdapter = new NotesListAdapter(this);
        mNotesListView.setAdapter(mNotesListAdapter);
        mAddNewNote = (Button) findViewById(R.id.btn_new_note);
        mAddNewNote.setOnClickListener(this);
        mAddNewNote.setOnTouchListener(new NewNoteOnTouchListener());
        mDispatch = false;
        mDispatchY = 0;
        mOriginY = 0;
        mTitleBar = (TextView) findViewById(R.id.tv_title_bar);
        mState = ListEditState.NOTE_LIST;
        mModeCallBack = new ModeCallback();
    }

    // 多选模式回调：负责 ActionMode 菜单与选择状态管理。
    private class ModeCallback implements ListView.MultiChoiceModeListener, OnMenuItemClickListener {
        private DropdownMenu mDropDownMenu;
        private ActionMode mActionMode;
        private MenuItem mMoveMenu;

        // 进入多选模式：初始化菜单项与下拉选择菜单。
        public boolean onCreateActionMode(ActionMode mode, Menu menu) {
            Log.d(TAG, "onCreateActionMode: mode=" + mode);
            forceShowActionModeBars();
            getMenuInflater().inflate(R.menu.note_list_options, menu);
            menu.findItem(R.id.delete).setOnMenuItemClickListener(this);
            MenuItem cancel = menu.findItem(R.id.action_mode_cancel);
            if (cancel != null) {
                cancel.setOnMenuItemClickListener(this);
            }
            mMoveMenu = menu.findItem(R.id.move);
            if (mFocusNoteDataItem.getParentId() == Notes.ID_CALL_RECORD_FOLDER
                    || DataUtils.getUserFolderCount(mContentResolver) == 0) {
                mMoveMenu.setVisible(false);
            } else {
                mMoveMenu.setVisible(true);
                mMoveMenu.setOnMenuItemClickListener(this);
            }
            mActionMode = mode;
            mCurrentActionMode = mode;
            mNotesListAdapter.setChoiceMode(true);
            mNotesListView.setLongClickable(false);
            mAddNewNote.setVisibility(View.GONE);

            View customView = LayoutInflater.from(NotesListActivity.this).inflate(
                    R.layout.note_list_dropdown_menu, null);
            mode.setCustomView(customView);
            mDropDownMenu = new DropdownMenu(NotesListActivity.this,
                    (Button) customView.findViewById(R.id.selection_menu),
                    R.menu.note_list_dropdown);
            mDropDownMenu.setOnDropdownMenuItemClickListener(new PopupMenu.OnMenuItemClickListener(){
                // 下拉菜单回调：切换全选/全不选。
                public boolean onMenuItemClick(MenuItem item) {
                    mNotesListAdapter.selectAll(!mNotesListAdapter.isAllSelected());
                    updateMenu();
                    return true;
                }

            });
            return true;
        }

        // 更新多选菜单显示：标题、全选状态与动作按钮可用性。
        private void updateMenu() {
            int selectedCount = mNotesListAdapter.getSelectedCount();
            // Update dropdown menu
            String format = getResources().getString(R.string.menu_select_title, selectedCount);
            mDropDownMenu.setTitle(format);
            MenuItem item = mDropDownMenu.findItem(R.id.action_select_all);
            if (item != null) {
                if (mNotesListAdapter.isAllSelected()) {
                    item.setChecked(true);
                    item.setTitle(R.string.menu_deselect_all);
                } else {
                    item.setChecked(false);
                    item.setTitle(R.string.menu_select_all);
                }
            }
        }

        // ActionMode 预处理（此处未使用）。
        public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
            // TODO Auto-generated method stub
            return false;
        }

        // ActionMode 点击（此处未使用）。
        public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
            // 兼容：部分机型点击底部 ActionMode 按钮只回调这里，不会触发 MenuItem 的 OnMenuItemClickListener。
            return onMenuItemClick(item);
        }

        // 退出多选模式：恢复列表与按钮状态。
        public void onDestroyActionMode(ActionMode mode) {
            Log.d(TAG, "onDestroyActionMode: mode=" + mode);
            mNotesListAdapter.setChoiceMode(false);
            mNotesListAdapter.notifyDataSetChanged();
            mNotesListView.clearChoices();
            mNotesListView.setLongClickable(true);
            mAddNewNote.setVisibility(View.VISIBLE);
            mActionMode = null;
            if (mCurrentActionMode == mode) {
                mCurrentActionMode = null;
            }

            // 强制隐藏底部栏（ROM 兼容）。
            forceHideActionModeBars();

            // 某些 ROM 会在回调后再次把 split bar 设为可见，这里延迟再隐藏一次。
            mNotesListView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    forceHideActionModeBars();
                }
            }, 80);

            // 再延迟一次：少量 ROM 在 100~200ms 内还会“回弹”一次。
            mNotesListView.postDelayed(new Runnable() {
                @Override
                public void run() {
                    forceHideActionModeBars();
                }
            }, 240);
        }

        // 主动结束多选模式。
        public void finishActionMode() {
            // 必须调用 ActionMode.finish() 才能让底部/顶部上下文操作栏消失。
            final ActionMode mode = (mActionMode != null) ? mActionMode : mCurrentActionMode;
            Log.d(TAG, "finishActionMode: mode=" + mode);
            if (mode != null) {
                mode.finish();
                // 兼容某些 ROM：finish 不立即生效，post 再次 finish 做强兜底。
                mNotesListView.post(new Runnable() {
                    @Override
                    public void run() {
                        if (mCurrentActionMode != null) {
                            Log.d(TAG, "finishActionMode(post): mode=" + mCurrentActionMode);
                            mCurrentActionMode.finish();
                        }
                        // 再兜底隐藏一次（ROM 兼容）。
                        forceHideActionModeBars();
                    }
                });
            } else {
                // 若 mode 为 null，也尝试隐藏残留底部栏（ROM 兼容）。
                forceHideActionModeBars();
            }
        }

        // 勾选变化：更新适配器选择状态并刷新菜单。
        public void onItemCheckedStateChanged(ActionMode mode, int position, long id,
                boolean checked) {
            mNotesListAdapter.setCheckedItem(position, checked);
            updateMenu();
        }

        // 多选菜单项点击：执行删除/移动等批量操作。
        public boolean onMenuItemClick(MenuItem item) {
            final int id = item.getItemId();
            if (id == R.id.action_mode_cancel) {
                Log.d(TAG, "onMenuItemClick: action_mode_cancel");
                finishActionMode();
                return true;
            }
            if (mNotesListAdapter.getSelectedCount() == 0) {
                Toast.makeText(NotesListActivity.this, getString(R.string.menu_select_none),
                        Toast.LENGTH_SHORT).show();
                return true;
            }

            // 迁移/构建适配改动说明（2026-03-25）：
            // - 将 switch(item.getItemId()) + case R.id.* 改为 if/else。
            // - 目的：避免在某些 AGP/构建配置下 R.id 不是编译期常量时触发“需要常量表达式”的编译错误。
            if (id == R.id.delete) {
                AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                builder.setTitle(getString(R.string.alert_title_delete));
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setMessage(getString(R.string.alert_message_delete_notes,
                                         mNotesListAdapter.getSelectedCount()));
                builder.setPositiveButton(android.R.string.ok,
                                         new DialogInterface.OnClickListener() {
                                             public void onClick(DialogInterface dialog,
                                                     int which) {
                                                 batchDelete();
                                             }
                                         });
                builder.setNegativeButton(android.R.string.cancel, null);
                builder.show();
            } else if (id == R.id.move) {
                startQueryDestinationFolders();
            } else {
                return false;
            }
            return true;
        }
    }

    // 新建按钮触摸代理：透明区域触摸下发给列表，以满足特定 UI 需求。
    private class NewNoteOnTouchListener implements OnTouchListener {

        // 触摸回调：根据触摸位置决定是否将事件转发给 ListView。
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN: {
                    Display display = getWindowManager().getDefaultDisplay();
                    int screenHeight = display.getHeight();
                    int newNoteViewHeight = mAddNewNote.getHeight();
                    int start = screenHeight - newNoteViewHeight;
                    int eventY = start + (int) event.getY();
                    /**
                     * Minus TitleBar's height
                     */
                    if (mState == ListEditState.SUB_FOLDER) {
                        eventY -= mTitleBar.getHeight();
                        start -= mTitleBar.getHeight();
                    }
                    /**
                     * HACKME:When click the transparent part of "New Note" button, dispatch
                     * the event to the list view behind this button. The transparent part of
                     * "New Note" button could be expressed by formula y=-0.12x+94（Unit:pixel）
                     * and the line top of the button. The coordinate based on left of the "New
                     * Note" button. The 94 represents maximum height of the transparent part.
                     * Notice that, if the background of the button changes, the formula should
                     * also change. This is very bad, just for the UI designer's strong requirement.
                     */
                    if (event.getY() < (event.getX() * (-0.12) + 94)) {
                        View view = mNotesListView.getChildAt(mNotesListView.getChildCount() - 1
                                - mNotesListView.getFooterViewsCount());
                        if (view != null && view.getBottom() > start
                                && (view.getTop() < (start + 94))) {
                            mOriginY = (int) event.getY();
                            mDispatchY = eventY;
                            event.setLocation(event.getX(), mDispatchY);
                            mDispatch = true;
                            return mNotesListView.dispatchTouchEvent(event);
                        }
                    }
                    break;
                }
                case MotionEvent.ACTION_MOVE: {
                    if (mDispatch) {
                        mDispatchY += (int) event.getY() - mOriginY;
                        event.setLocation(event.getX(), mDispatchY);
                        return mNotesListView.dispatchTouchEvent(event);
                    }
                    break;
                }
                default: {
                    if (mDispatch) {
                        event.setLocation(event.getX(), mDispatchY);
                        mDispatch = false;
                        return mNotesListView.dispatchTouchEvent(event);
                    }
                    break;
                }
            }
            return false;
        }

    };

    // 异步查询笔记列表 Cursor。
    private void startAsyncNotesListQuery() {
        String selection = (mCurrentFolderId == Notes.ID_ROOT_FOLDER) ? ROOT_FOLDER_SELECTION
                : NORMAL_SELECTION;
        mBackgroundQueryHandler.startQuery(FOLDER_NOTE_LIST_QUERY_TOKEN, null,
                Notes.CONTENT_NOTE_URI, NoteItemData.PROJECTION, selection, new String[] {
                    String.valueOf(mCurrentFolderId)
                }, NoteColumns.TYPE + " DESC," + NoteColumns.MODIFIED_DATE + " DESC");
    }

    // 后台查询处理器：接收查询结果并更新 UI。
    private final class BackgroundQueryHandler extends AsyncQueryHandler {
        // 构造：绑定 ContentResolver。
        public BackgroundQueryHandler(ContentResolver contentResolver) {
            super(contentResolver);
        }

        @Override
        // 查询回调：更新列表 Cursor 或展示文件夹选择菜单。
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            switch (token) {
                case FOLDER_NOTE_LIST_QUERY_TOKEN:
                    mNotesListAdapter.changeCursor(cursor);
                    break;
                case FOLDER_LIST_QUERY_TOKEN:
                    if (cursor != null && cursor.getCount() > 0) {
                        showFolderListMenu(cursor);
                    } else {
                        Log.e(TAG, "Query folder failed");
                    }
                    break;
                default:
                    return;
            }
        }
    }

    // 显示“移动到文件夹”列表对话框。
    private void showFolderListMenu(Cursor cursor) {
        AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
        builder.setTitle(R.string.menu_title_select_folder);
        final FoldersListAdapter adapter = new FoldersListAdapter(this, cursor);
        builder.setAdapter(adapter, new DialogInterface.OnClickListener() {

            // 单击回调：将选中笔记批量移动到目标文件夹。
            public void onClick(DialogInterface dialog, int which) {
                DataUtils.batchMoveToFolder(mContentResolver,
                        mNotesListAdapter.getSelectedItemIds(), adapter.getItemId(which));
                Toast.makeText(
                        NotesListActivity.this,
                        getString(R.string.format_move_notes_to_folder,
                                mNotesListAdapter.getSelectedCount(),
                                adapter.getFolderName(NotesListActivity.this, which)),
                        Toast.LENGTH_SHORT).show();
                mModeCallBack.finishActionMode();
            }
        });
        builder.show();
    }

    // 新建笔记：打开编辑页并带上当前文件夹 id。
    private void createNewNote() {
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_INSERT_OR_EDIT);
        intent.putExtra(Notes.INTENT_EXTRA_FOLDER_ID, mCurrentFolderId);
        this.startActivityForResult(intent, REQUEST_CODE_NEW_NODE);
    }

    // 批量删除：异步执行删除/移入回收站并更新相关挂件。
    private void batchDelete() {
        new AsyncTask<Void, Void, HashSet<AppWidgetAttribute>>() {
            // 后台任务：执行批量删除/移动到回收站并返回受影响挂件。
            protected HashSet<AppWidgetAttribute> doInBackground(Void... unused) {
                HashSet<AppWidgetAttribute> widgets = mNotesListAdapter.getSelectedWidget();
                if (!isSyncMode()) {
                    // if not synced, delete notes directly
                    if (DataUtils.batchDeleteNotes(mContentResolver, mNotesListAdapter
                            .getSelectedItemIds())) {
                    } else {
                        Log.e(TAG, "Delete notes error, should not happens");
                    }
                } else {
                    // in sync mode, we'll move the deleted note into the trash
                    // folder
                    if (!DataUtils.batchMoveToFolder(mContentResolver, mNotesListAdapter
                            .getSelectedItemIds(), Notes.ID_TRASH_FOLER)) {
                        Log.e(TAG, "Move notes to trash folder error, should not happens");
                    }
                }
                return widgets;
            }

            @Override
            // 完成回调：刷新挂件并结束多选模式。
            protected void onPostExecute(HashSet<AppWidgetAttribute> widgets) {
                if (widgets != null) {
                    for (AppWidgetAttribute widget : widgets) {
                        if (widget.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                                && widget.widgetType != Notes.TYPE_WIDGET_INVALIDE) {
                            updateWidget(widget.widgetId, widget.widgetType);
                        }
                    }
                }
                mModeCallBack.finishActionMode();
            }
        }.execute();
    }

    // 删除文件夹：删除或移入回收站，并更新文件夹下笔记的挂件。
    private void deleteFolder(long folderId) {
        if (folderId == Notes.ID_ROOT_FOLDER) {
            Log.e(TAG, "Wrong folder id, should not happen " + folderId);
            return;
        }

        HashSet<Long> ids = new HashSet<Long>();
        ids.add(folderId);
        HashSet<AppWidgetAttribute> widgets = DataUtils.getFolderNoteWidget(mContentResolver,
                folderId);
        if (!isSyncMode()) {
            // if not synced, delete folder directly
            DataUtils.batchDeleteNotes(mContentResolver, ids);
        } else {
            // in sync mode, we'll move the deleted folder into the trash folder
            DataUtils.batchMoveToFolder(mContentResolver, ids, Notes.ID_TRASH_FOLER);
        }
        if (widgets != null) {
            for (AppWidgetAttribute widget : widgets) {
                if (widget.widgetId != AppWidgetManager.INVALID_APPWIDGET_ID
                        && widget.widgetType != Notes.TYPE_WIDGET_INVALIDE) {
                    updateWidget(widget.widgetId, widget.widgetType);
                }
            }
        }
    }

    // 打开笔记：跳转到编辑页查看/编辑。
    private void openNode(NoteItemData data) {
        Intent intent = new Intent(this, NoteEditActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra(Intent.EXTRA_UID, data.getId());
        this.startActivityForResult(intent, REQUEST_CODE_OPEN_NODE);
    }

    // 打开文件夹：切换列表查询范围并更新标题栏与状态。
    private void openFolder(NoteItemData data) {
        mCurrentFolderId = data.getId();
        startAsyncNotesListQuery();
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mState = ListEditState.CALL_RECORD_FOLDER;
            mAddNewNote.setVisibility(View.GONE);
        } else {
            mState = ListEditState.SUB_FOLDER;
        }
        if (data.getId() == Notes.ID_CALL_RECORD_FOLDER) {
            mTitleBar.setText(R.string.call_record_folder_name);
        } else {
            mTitleBar.setText(data.getSnippet());
        }
        mTitleBar.setVisibility(View.VISIBLE);
    }

    // 点击回调：处理“新建笔记”按钮。
    public void onClick(View v) {
        final int id = v.getId();
        if (id == R.id.btn_new_note) {
            createNewNote();
        }
    }

    // 显示软键盘（用于文件夹命名输入）。
    private void showSoftInput() {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (inputMethodManager != null) {
            inputMethodManager.toggleSoftInput(InputMethodManager.SHOW_FORCED, 0);
        }
    }

    // 隐藏软键盘。
    private void hideSoftInput(View view) {
        InputMethodManager inputMethodManager = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        inputMethodManager.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    // 创建/重命名文件夹对话框。
    private void showCreateOrModifyFolderDialog(final boolean create) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        View view = LayoutInflater.from(this).inflate(R.layout.dialog_edit_text, null);
        final EditText etName = (EditText) view.findViewById(R.id.et_foler_name);
        showSoftInput();
        if (!create) {
            if (mFocusNoteDataItem != null) {
                etName.setText(mFocusNoteDataItem.getSnippet());
                builder.setTitle(getString(R.string.menu_folder_change_name));
            } else {
                Log.e(TAG, "The long click data item is null");
                return;
            }
        } else {
            etName.setText("");
            builder.setTitle(this.getString(R.string.menu_create_folder));
        }

        builder.setPositiveButton(android.R.string.ok, null);
        builder.setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
            // 取消回调：隐藏键盘。
            public void onClick(DialogInterface dialog, int which) {
                hideSoftInput(etName);
            }
        });

        final Dialog dialog = builder.setView(view).show();
        final Button positive = (Button)dialog.findViewById(android.R.id.button1);
        positive.setOnClickListener(new OnClickListener() {
            // 确认回调：校验名称并执行创建/重命名。
            public void onClick(View v) {
                hideSoftInput(etName);
                String name = etName.getText().toString();
                if (DataUtils.checkVisibleFolderName(mContentResolver, name)) {
                    Toast.makeText(NotesListActivity.this, getString(R.string.folder_exist, name),
                            Toast.LENGTH_LONG).show();
                    etName.setSelection(0, etName.length());
                    return;
                }
                if (!create) {
                    if (!TextUtils.isEmpty(name)) {
                        ContentValues values = new ContentValues();
                        values.put(NoteColumns.SNIPPET, name);
                        values.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);
                        values.put(NoteColumns.LOCAL_MODIFIED, 1);
                        mContentResolver.update(Notes.CONTENT_NOTE_URI, values, NoteColumns.ID
                                + "=?", new String[] {
                            String.valueOf(mFocusNoteDataItem.getId())
                        });
                    }
                } else if (!TextUtils.isEmpty(name)) {
                    ContentValues values = new ContentValues();
                    values.put(NoteColumns.SNIPPET, name);
                    values.put(NoteColumns.TYPE, Notes.TYPE_FOLDER);
                    mContentResolver.insert(Notes.CONTENT_NOTE_URI, values);
                }
                dialog.dismiss();
            }
        });

        if (TextUtils.isEmpty(etName.getText())) {
            positive.setEnabled(false);
        }
        /**
         * When the name edit text is null, disable the positive button
         */
        etName.addTextChangedListener(new TextWatcher() {
            // 文本变化前回调（未使用）。
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                // TODO Auto-generated method stub

            }

            // 文本变化中回调：控制“确定”按钮可用状态。
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (TextUtils.isEmpty(etName.getText())) {
                    positive.setEnabled(false);
                } else {
                    positive.setEnabled(true);
                }
            }

            // 文本变化后回调（未使用）。
            public void afterTextChanged(Editable s) {
                // TODO Auto-generated method stub

            }
        });
    }

    @Override
    // 返回键处理：在子文件夹/通话记录页返回到根列表，否则退出。
    public void onBackPressed() {
        // 多选模式优先退出：避免用户卡在 ActionMode（底部删除栏）无法回到初始列表界面。
        if (mNotesListAdapter != null && mNotesListAdapter.isInChoiceMode()) {
            Log.d(TAG, "onBackPressed: exit choice mode");
            if (mModeCallBack != null) {
                mModeCallBack.finishActionMode();
            }
            forceHideActionModeBars();
            return;
        }
        switch (mState) {
            case SUB_FOLDER:
                mCurrentFolderId = Notes.ID_ROOT_FOLDER;
                mState = ListEditState.NOTE_LIST;
                startAsyncNotesListQuery();
                mTitleBar.setVisibility(View.GONE);
                break;
            case CALL_RECORD_FOLDER:
                mCurrentFolderId = Notes.ID_ROOT_FOLDER;
                mState = ListEditState.NOTE_LIST;
                mAddNewNote.setVisibility(View.VISIBLE);
                mTitleBar.setVisibility(View.GONE);
                startAsyncNotesListQuery();
                break;
            case NOTE_LIST:
                super.onBackPressed();
                break;
            default:
                break;
        }
    }

    // 更新桌面挂件：发送广播通知对应 provider 刷新。
    private void updateWidget(int appWidgetId, int appWidgetType) {
        Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_UPDATE);
        if (appWidgetType == Notes.TYPE_WIDGET_2X) {
            intent.setClass(this, NoteWidgetProvider_2x.class);
        } else if (appWidgetType == Notes.TYPE_WIDGET_4X) {
            intent.setClass(this, NoteWidgetProvider_4x.class);
        } else {
            Log.e(TAG, "Unspported widget type");
            return;
        }

        intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, new int[] {
            appWidgetId
        });

        sendBroadcast(intent);
        setResult(RESULT_OK, intent);
    }

    private final OnCreateContextMenuListener mFolderOnCreateContextMenuListener = new OnCreateContextMenuListener() {
        // 创建文件夹右键菜单：查看/删除/重命名。
        public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
            if (mFocusNoteDataItem != null) {
                menu.setHeaderTitle(mFocusNoteDataItem.getSnippet());
                menu.add(0, MENU_FOLDER_VIEW, 0, R.string.menu_folder_view);
                menu.add(0, MENU_FOLDER_DELETE, 0, R.string.menu_folder_delete);
                menu.add(0, MENU_FOLDER_CHANGE_NAME, 0, R.string.menu_folder_change_name);
            }
        }
    };

    @Override
    // 右键菜单关闭：清理 ContextMenuListener。
    public void onContextMenuClosed(Menu menu) {
        if (mNotesListView != null) {
            mNotesListView.setOnCreateContextMenuListener(null);
        }
        super.onContextMenuClosed(menu);
    }

    @Override
    // 右键菜单点击：处理文件夹查看/删除/重命名。
    public boolean onContextItemSelected(MenuItem item) {
        if (mFocusNoteDataItem == null) {
            Log.e(TAG, "The long click data item is null");
            return false;
        }
        switch (item.getItemId()) {
            case MENU_FOLDER_VIEW:
                openFolder(mFocusNoteDataItem);
                break;
            case MENU_FOLDER_DELETE:
                AlertDialog.Builder builder = new AlertDialog.Builder(this);
                builder.setTitle(getString(R.string.alert_title_delete));
                builder.setIcon(android.R.drawable.ic_dialog_alert);
                builder.setMessage(getString(R.string.alert_message_delete_folder));
                builder.setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            // 确认回调：删除目标文件夹。
                            public void onClick(DialogInterface dialog, int which) {
                                deleteFolder(mFocusNoteDataItem.getId());
                            }
                        });
                builder.setNegativeButton(android.R.string.cancel, null);
                builder.show();
                break;
            case MENU_FOLDER_CHANGE_NAME:
                showCreateOrModifyFolderDialog(false);
                break;
            default:
                break;
        }

        return true;
    }

    @Override
    // 菜单准备：根据当前状态加载不同菜单。
    public boolean onPrepareOptionsMenu(Menu menu) {
        menu.clear();
        if (mState == ListEditState.NOTE_LIST) {
            getMenuInflater().inflate(R.menu.note_list, menu);
            // set sync or sync_cancel
            menu.findItem(R.id.menu_sync).setTitle(
                    GTaskSyncService.isSyncing() ? R.string.menu_sync_cancel : R.string.menu_sync);
        } else if (mState == ListEditState.SUB_FOLDER) {
            getMenuInflater().inflate(R.menu.sub_folder, menu);
        } else if (mState == ListEditState.CALL_RECORD_FOLDER) {
            getMenuInflater().inflate(R.menu.call_record_folder, menu);
        } else {
            Log.e(TAG, "Wrong state:" + mState);
        }
        return true;
    }

    @Override
    // 菜单点击：处理新建文件夹/导出/同步/设置/新建笔记/搜索。
    public boolean onOptionsItemSelected(MenuItem item) {
        // 迁移/构建适配改动说明（2026-03-25）：
        // - 将 switch(item.getItemId()) + case R.id.* 改为 if/else。
        // - 目的：避免在某些 AGP/构建配置下 R.id 不是编译期常量时触发“需要常量表达式”的编译错误。
        final int id = item.getItemId();
        if (id == R.id.menu_new_folder) {
            showCreateOrModifyFolderDialog(true);
        } else if (id == R.id.menu_export_text) {
            exportNoteToText();
        } else if (id == R.id.menu_sync) {
            if (isSyncMode()) {
                if (TextUtils.equals(item.getTitle(), getString(R.string.menu_sync))) {
                    GTaskSyncService.startSync(this);
                } else {
                    GTaskSyncService.cancelSync(this);
                }
            } else {
                startPreferenceActivity();
            }
        } else if (id == R.id.menu_setting) {
            startPreferenceActivity();
        } else if (id == R.id.menu_new_note) {
            createNewNote();
        } else if (id == R.id.menu_search) {
            onSearchRequested();
        }
        return true;
    }

    @Override
    // 触发系统搜索。
    public boolean onSearchRequested() {
        startSearch(null, false, null /* appData */, false);
        return true;
    }

    // 导出：将所有笔记导出为文本文件（异步执行）。
    private void exportNoteToText() {
        final BackupUtils backup = BackupUtils.getInstance(NotesListActivity.this);
        new AsyncTask<Void, Void, Integer>() {

            @Override
            // 后台任务：执行导出并返回状态码。
            protected Integer doInBackground(Void... unused) {
                return backup.exportToText();
            }

            @Override
            // 完成回调：弹窗提示导出结果。
            protected void onPostExecute(Integer result) {
                if (result == BackupUtils.STATE_SD_CARD_UNMOUONTED) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(NotesListActivity.this
                            .getString(R.string.failed_sdcard_export));
                    builder.setMessage(NotesListActivity.this
                            .getString(R.string.error_sdcard_unmounted));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.show();
                } else if (result == BackupUtils.STATE_SUCCESS) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(NotesListActivity.this
                            .getString(R.string.success_sdcard_export));
                    builder.setMessage(NotesListActivity.this.getString(
                            R.string.format_exported_file_location, backup
                                    .getExportedTextFileName(), backup.getExportedTextFileDir()));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.show();
                } else if (result == BackupUtils.STATE_SYSTEM_ERROR) {
                    AlertDialog.Builder builder = new AlertDialog.Builder(NotesListActivity.this);
                    builder.setTitle(NotesListActivity.this
                            .getString(R.string.failed_sdcard_export));
                    builder.setMessage(NotesListActivity.this
                            .getString(R.string.error_sdcard_export));
                    builder.setPositiveButton(android.R.string.ok, null);
                    builder.show();
                }
            }

        }.execute();
    }

    // 是否处于同步模式（已设置同步账号）。
    private boolean isSyncMode() {
        return NotesPreferenceActivity.getSyncAccountName(this).trim().length() > 0;
    }

    // 打开设置页（用于非同步模式下的“同步”入口）。
    private void startPreferenceActivity() {
        Activity from = getParent() != null ? getParent() : this;
        Intent intent = new Intent(from, NotesPreferenceActivity.class);
        from.startActivityIfNeeded(intent, -1);
    }

    // 列表点击监听：根据当前状态打开文件夹或笔记，或在多选模式下切换勾选。
    private class OnListItemClickListener implements OnItemClickListener {

        // 点击回调：处理单击列表项。
        public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
            if (view instanceof NotesListItem) {
                NoteItemData item = ((NotesListItem) view).getItemData();
                if (mNotesListAdapter.isInChoiceMode()) {
                    if (item.getType() == Notes.TYPE_NOTE) {
                        position = position - mNotesListView.getHeaderViewsCount();
                        mModeCallBack.onItemCheckedStateChanged(null, position, id,
                                !mNotesListAdapter.isSelectedItem(position));
                    }
                    return;
                }

                switch (mState) {
                    case NOTE_LIST:
                        if (item.getType() == Notes.TYPE_FOLDER
                                || item.getType() == Notes.TYPE_SYSTEM) {
                            openFolder(item);
                        } else if (item.getType() == Notes.TYPE_NOTE) {
                            openNode(item);
                        } else {
                            Log.e(TAG, "Wrong note type in NOTE_LIST");
                        }
                        break;
                    case SUB_FOLDER:
                    case CALL_RECORD_FOLDER:
                        if (item.getType() == Notes.TYPE_NOTE) {
                            openNode(item);
                        } else {
                            Log.e(TAG, "Wrong note type in SUB_FOLDER");
                        }
                        break;
                    default:
                        break;
                }
            }
        }

    }

    // 查询可移动的目标文件夹列表。
    private void startQueryDestinationFolders() {
        String selection = NoteColumns.TYPE + "=? AND " + NoteColumns.PARENT_ID + "<>? AND " + NoteColumns.ID + "<>?";
        selection = (mState == ListEditState.NOTE_LIST) ? selection:
            "(" + selection + ") OR (" + NoteColumns.ID + "=" + Notes.ID_ROOT_FOLDER + ")";

        mBackgroundQueryHandler.startQuery(FOLDER_LIST_QUERY_TOKEN,
                null,
                Notes.CONTENT_NOTE_URI,
                FoldersListAdapter.PROJECTION,
                selection,
                new String[] {
                        String.valueOf(Notes.TYPE_FOLDER),
                        String.valueOf(Notes.ID_TRASH_FOLER),
                        String.valueOf(mCurrentFolderId)
                },
                NoteColumns.MODIFIED_DATE + " DESC");
    }

    // 长按回调：进入多选模式或显示文件夹右键菜单。
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (view instanceof NotesListItem) {
            mFocusNoteDataItem = ((NotesListItem) view).getItemData();
            if (mFocusNoteDataItem.getType() == Notes.TYPE_NOTE && !mNotesListAdapter.isInChoiceMode()) {
                // 使用 Activity.startActionMode()：部分 ROM 下从 ListView 启动会出现底部栏不消失的问题。
                ActionMode mode = NotesListActivity.this.startActionMode(mModeCallBack);
                mCurrentActionMode = mode;
                if (mode != null) {
                    mModeCallBack.onItemCheckedStateChanged(null, position, id, true);
                    mNotesListView.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                } else {
                    Log.e(TAG, "startActionMode fails");
                }
                return true;
            } else if (mFocusNoteDataItem.getType() == Notes.TYPE_FOLDER) {
                mNotesListView.setOnCreateContextMenuListener(mFolderOnCreateContextMenuListener);
            }
        }
        return false;
    }
}
