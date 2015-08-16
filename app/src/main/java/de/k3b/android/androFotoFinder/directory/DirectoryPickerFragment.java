/*
 * Copyright (c) 2015 by k3b.
 *
 * This file is part of AndroFotoFinder.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License
 * for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>
 */
 
package de.k3b.android.androFotoFinder.directory;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.DialogFragment;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.provider.MediaStore;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

// import com.squareup.leakcanary.RefWatcher;

import de.k3b.android.androFotoFinder.queries.FotoSql;
import de.k3b.android.androFotoFinder.queries.FotoViewerParameter;
import de.k3b.android.androFotoFinder.Global;
import de.k3b.android.androFotoFinder.R;
import de.k3b.android.util.AndroidFileCommands;
import de.k3b.io.Directory;
import de.k3b.io.DirectoryNavigator;
import de.k3b.io.IDirectory;
import de.k3b.io.OSDirectory;

import java.util.List;

/**
 * A fragment with a Listing of Directories to be picked.
 *
 * [parentPathBar]
 * [treeView]
 *
 * Activities that contain this fragment must implement the
 * {@link OnDirectoryInteractionListener} interface
 * to handle interaction events.
 */
public class DirectoryPickerFragment extends DialogFragment implements DirectoryGui {

    private static final String TAG = "DirFragment";
    private static final java.lang.String INSTANCE_STATE_CONTEXT_MENU = "contextmenu";

    // public state
    private IDirectory mCurrentSelection = null;

    // Layout
    private HorizontalScrollView parentPathBarScroller;
    private LinearLayout parentPathBar;
    private ExpandableListView treeView;
    private TextView status = null;
    private Button cmdOk = null;
    private Button cmdCancel = null;
    private Button cmdPopup = null;

    private View.OnClickListener pathButtonClickHandler;
    private View.OnLongClickListener pathButtonLongClickHandler = null;
    // local data
    protected Activity mContext;
    private DirectoryListAdapter mAdapter;
    private DirectoryNavigator mNavigation;
    private int mDirTypId = 0;

    // api to fragment owner
    private OnDirectoryInteractionListener mDirectoryListener;

    // for debugging
    private static int id = 1;
    private final String debugPrefix;
    private ImageView mImage;
    private int mLastIconID = -1;
    private int mContextMenue = 0;

    // not null when renumber dialog is open.
    private AlertDialog mSubDialog = null;

    // onsorted generated by ide-s autocomplete


    public DirectoryPickerFragment() {
        // Required empty public constructor
        debugPrefix = "DirectoryPickerFragment#" + (id++)  + " ";
        Global.debugMemory(debugPrefix, "ctor");
        // Required empty public constructor
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, debugPrefix + "()");
        }
    }

    public DirectoryPickerFragment setContextMenuId(int contextMenuId) {
        mContextMenue = contextMenuId;
        return this;
    }

    /****** live cycle ********/
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        if (savedInstanceState != null) {
            this.mContextMenue = savedInstanceState.getInt(INSTANCE_STATE_CONTEXT_MENU, this.mContextMenue);
        }

        // Inflate the layout for this fragment
        View view = inflater.inflate(R.layout.fragment_directory, container, false);

        mContext = this.getActivity();

        pathButtonClickHandler = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onParentPathBarButtonClick((IDirectory) v.getTag());
            }
        };

        if (this.mContextMenue != 0) {
            pathButtonLongClickHandler = new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    onShowPopUp(v, (IDirectory) v.getTag());
                    return true;
                }
            };
        }

        this.parentPathBar = (LinearLayout) view.findViewById(R.id.parent_owner);
        this.parentPathBarScroller = (HorizontalScrollView) view.findViewById(R.id.parent_scroller);

        treeView = (ExpandableListView)view.findViewById(R.id.directory_tree);
        treeView.setOnChildClickListener(new ExpandableListView.OnChildClickListener() {
            @Override
            public boolean onChildClick(ExpandableListView parent, View v, int groupPosition, int childPosition, long id) {
                return DirectoryPickerFragment.this.onChildDirectoryClick(childPosition, mNavigation.getChild(groupPosition, childPosition));
            }
        });

        treeView.setOnGroupClickListener(new ExpandableListView.OnGroupClickListener() {
            @Override
            public boolean onGroupClick(ExpandableListView parent, View v, int groupPosition, long id) {
                return DirectoryPickerFragment.this.onParentDirectoryClick(mNavigation.getGroup(groupPosition));
            }
        });

        if (mContextMenue != 0) {
            treeView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
                @Override
                public boolean onItemLongClick(AdapterView<?> parent, View view, int flatPosition, long id) {
                    long packedPos = treeView.getExpandableListPosition(flatPosition);
                    int group = treeView.getPackedPositionGroup(packedPos);
                    int child = treeView.getPackedPositionChild(packedPos);
                    IDirectory directory = (child != -1) ? mNavigation.getChild(group, child) : mNavigation.getGroup(group);
                    onShowPopUp(view, directory);
                    return false;
                }
            });
        }

        this.mImage = (ImageView) view.findViewById(R.id.image);

        if (getShowsDialog()) {
            onCreateViewDialog(view);
        }

        // does nothing if defineDirectoryNavigation() has not been called yet
        reloadTreeViewIfAvailable();
        super.onCreate(savedInstanceState);
        return view;
    }

    /** handle init for dialog-only controlls: cmdOk, cmdCancel, status */
    private void onCreateViewDialog(View view) {
        this.status = (TextView) view.findViewById(R.id.status);
        this.status.setVisibility(View.VISIBLE);
        
        this.cmdOk = (Button) view.findViewById(R.id.cmd_ok);
        this.cmdOk.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDirectoryPick(mCurrentSelection);
            }
        });
        cmdOk.setVisibility(View.VISIBLE);
        
        cmdCancel = (Button) view.findViewById(R.id.cmd_cancel);
        cmdCancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onDirectoryCancel();
            }
        });
        cmdCancel.setVisibility(View.VISIBLE);

        cmdPopup = null;
        if (mContextMenue != 0) {
            cmdPopup = (Button) view.findViewById(R.id.cmd_popup);
            cmdPopup.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onShowPopUp(cmdPopup, mCurrentSelection);
                }
            });
            cmdPopup.setVisibility(View.VISIBLE);
        }

        if (mDirTypId != 0) {
            String title = mContext.getString(
                    R.string.directory_fragment_dialog_title,
                    FotoSql.getName(mContext,mDirTypId));
            getDialog().setTitle(title);
            // no api for setIcon ????
        }
    }

    /** called via pathBar-Button-LongClick, tree-item-LongClick, popUp-button */
    private void onShowPopUp(View anchor, IDirectory selection) {
        PopupMenu popup = new PopupMenu(getActivity(), anchor);
        popup.setOnMenuItemClickListener(popUpListener);
        MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(this.mContextMenue, popup.getMenu());
        mPopUpSelection = selection;
        popup.show();
    }

    private IDirectory mPopUpSelection = null;
    private final PopupMenu.OnMenuItemClickListener popUpListener = new PopupMenu.OnMenuItemClickListener() {
        @Override
        public boolean onMenuItemClick(MenuItem menuItem) {
            return onPopUpClick(menuItem);
        }
    };

    private boolean onPopUpClick(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.cmd_mk_dir:
                return onCreateSubDirQueston(mPopUpSelection);
        }
        return false;
    }

    private boolean onCreateSubDirQueston(final IDirectory parentDir) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        builder.setTitle(R.string.cmd_mk_dir);
        View content = getActivity().getLayoutInflater().inflate(R.layout.dialog_edit_name, null);

        final EditText edit = (EditText) content.findViewById(R.id.edName);
        String defaultName = getString(R.string.mk_dir_default);
        edit.setText(defaultName);
        edit.setSelection(0, defaultName.length());

        builder.setView(content);
        builder.setNegativeButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                dismiss();mSubDialog=null;
            }
        });
        builder.setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
            //@Override
            public void onClick(DialogInterface dialog, int which) {
                onCreateSubDirAnswer(parentDir, edit.getText().toString());
                mSubDialog=null;
            }
        });
        AlertDialog alertDialog = builder.create();
        alertDialog.show();

        int width = (int ) (8 * edit.getTextSize());
        // DisplayMetrics metrics = getResources().getDisplayMetrics();
        // int width = metrics.widthPixels;
        alertDialog.getWindow().setLayout(width*2, LinearLayout.LayoutParams.WRAP_CONTENT);
        mSubDialog = alertDialog;
        return true;
    }

    private void onCreateSubDirAnswer(final IDirectory parentDir, String newCildFolderName) {
        OSDirectory newChild = ((OSDirectory) parentDir).addChildFolder(newCildFolderName);
        String newPathAbsolute = newChild.getAbsolute();

        int msgId;
        if (newChild.osMkDirs()) {
            AndroidFileCommands.log(getActivity(), "mkdirs \"", newPathAbsolute, "\"" ).closeLogFile();
            msgId = R.string.cmd_mk_success;
            reloadTreeViewIfAvailable();
            onParentPathBarButtonClick(newChild);
        } else {
            msgId = R.string.cmd_mk_failed;
            parentDir.getChildren().remove(newChild);
            newChild.destroy();
        }
        Toast.makeText(getActivity(), getActivity().getString(msgId, newPathAbsolute),
                Toast.LENGTH_LONG).show();
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(INSTANCE_STATE_CONTEXT_MENU, mContextMenue);
    }

    private void onDirectoryCancel() {
        Log.d(Global.LOG_CONTEXT, debugPrefix + "onCancel: " + mCurrentSelection);
        mDirectoryListener.onDirectoryCancel(mDirTypId);
        dismiss();
    }

    protected void onDirectoryPick(IDirectory selection) {
        Log.d(Global.LOG_CONTEXT, debugPrefix + "onOk: " + selection);
        if (selection != null) {
            mDirectoryListener.onDirectoryPick(selection.getAbsolute()
                    , mDirTypId);
            dismiss();
        }
    }

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        Dialog result = super.onCreateDialog(savedInstanceState);

        return result;
    };

    public void onResume() {
        super.onResume();

        // after rotation dlg has no more data: close it
        if (this.getShowsDialog() && (this.mNavigation == null)) {
            dismiss();
        }
    }

    @Override public void onDestroy() {
        if (mSubDialog != null) mSubDialog.dismiss();
        mSubDialog = null;
        super.onDestroy();
        // RefWatcher refWatcher = AndroFotoFinderApp.getRefWatcher(getActivity());
        // refWatcher.watch(this);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mDirectoryListener = (OnDirectoryInteractionListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString()
                    + " must implement OnDirectoryInteractionListener");
        }
    }

    @Override
    public void onDetach() {
        super.onDetach();
        mDirectoryListener = null;
    }

    /*********************** gui interaction *******************************************/
    private boolean onParentDirectoryClick(IDirectory dir) {
        updateParentPathBar(dir);
        notifySelectionChanged(dir);
        return false;
    }

    private boolean onChildDirectoryClick(int childPosition, IDirectory selectedChild) {
        Log.d(TAG, debugPrefix + "onChildDirectoryClick(" +
                selectedChild.getAbsolute() + ")");

        // naviationchange only if there are children below child
        IDirectory newGrandParent = ((selectedChild != null) && (Directory.getChildCount(selectedChild) > 0)) ? selectedChild.getParent() : null;

        navigateTo(childPosition, newGrandParent);
        updateParentPathBar(selectedChild);
        notifySelectionChanged(selectedChild);
        return false;
    }

    private void onParentPathBarButtonClick(IDirectory selectedChild) {
        Log.d(TAG, debugPrefix + "onParentPathBarButtonClick(" +
                selectedChild.getAbsolute() + ")");

        // naviationchange only if there are children below child
        IDirectory newGrandParent = ((selectedChild != null) && (Directory.getChildCount(selectedChild) > 0)) ? selectedChild.getParent() : null;
        List<IDirectory> siblings = (newGrandParent != null) ? newGrandParent.getChildren() : null;

        if (siblings != null) {
            int childPosition = siblings.indexOf(selectedChild);
            navigateTo(childPosition, newGrandParent);
        }
        updateParentPathBar(selectedChild);
        notifySelectionChanged(selectedChild);
    }

    protected void notifySelectionChanged(IDirectory selectedChild) {
        if ((mDirectoryListener != null) && (selectedChild != null)) mDirectoryListener.onDirectorySelectionChanged(selectedChild.getAbsolute(), mDirTypId);
    }

    private void updateStatus() {
        int itemCount = getItemCount(mCurrentSelection);
        boolean canPressOk = (itemCount > 0);

        if (cmdOk != null) cmdOk.setEnabled(canPressOk);
        if (cmdPopup != null) cmdPopup.setEnabled(canPressOk);
        
        if (status != null) {
            if (canPressOk) {
                status.setText(this.mCurrentSelection.getAbsolute());
            } else {
                status.setText(R.string.no_dir_selected);
            }
        }
    }

    private int getItemCount(IDirectory _directory) {
        if ((_directory == null) || (!(_directory instanceof Directory))) return 1;

        Directory directory = (Directory) _directory;
        return (FotoViewerParameter.includeSubItems)
                        ? directory.getNonDirSubItemCount()
                        : directory.getNonDirItemCount();
    }

    /*********************** local helper *******************************************/
    private void updateParentPathBar(String currentSelection) {
        if (this.mNavigation != null) {
            updateParentPathBar(this.mNavigation.getRoot().find(currentSelection));
        }
    }

    private void updateParentPathBar(IDirectory selectedChild) {
        parentPathBar.removeAllViews();

        if (selectedChild != null) {

            Button first = null;
            IDirectory current = selectedChild;
            while (current.getParent() != null) {
                Button button = createPathButton(current);
                // add parent left to chlild
                // gui order root/../child.parent/child
                parentPathBar.addView(button, 0);
                if (first == null) first = button;
                current = current.getParent();
            }

            // scroll to right where deepest child is
            parentPathBarScroller.requestChildFocus(parentPathBar, first);
        }

        if (mImage != null) {
            updateBitmap(selectedChild.getIconID());
        }

        this.mCurrentSelection = selectedChild;

        updateStatus();
    }

    private void updateBitmap(int iconID) {
        if (Global.debugEnabledViewItem) {
            Log.d(Global.LOG_CONTEXT, debugPrefix + "updateBitmap#" + iconID);
        }
        if (mLastIconID != iconID) {
            mLastIconID = iconID;
            if (mLastIconID == 0) {
                this.mImage.setVisibility(View.GONE);
            } else {
                this.mImage.setVisibility(View.VISIBLE);
                this.mImage.setImageBitmap(getBitmap(mLastIconID));
            }
        }
    }

    private Bitmap getBitmap(int id) {
        final Bitmap thumbnail = MediaStore.Images.Thumbnails.getThumbnail(
                getActivity().getContentResolver(),
                id,
                MediaStore.Images.Thumbnails.MICRO_KIND,
                new BitmapFactory.Options());

        return thumbnail;
    }


    private Button createPathButton(IDirectory currentDir) {
        Button result = new Button(getActivity());
        result.setTag(currentDir);
        result.setText(DirectoryListAdapter.getDirectoryDisplayText(null, currentDir, (FotoViewerParameter.includeSubItems) ? Directory.OPT_SUB_ITEM : Directory.OPT_ITEM));

        result.setOnClickListener(pathButtonClickHandler);
        if (pathButtonLongClickHandler != null) {
            result.setOnLongClickListener(pathButtonLongClickHandler);
        }
        return result;
    }

    /**
     * DirectoryGui-Public api for embedding activity
     * (Re)-Defines base parameters for Directory Navigation
     * @param root
     * @param dirTypId
     * @param initialAbsolutePath
     */
    @Override
    public void defineDirectoryNavigation(IDirectory root, int dirTypId, String initialAbsolutePath) {
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, debugPrefix + " defineDirectoryNavigation : " + initialAbsolutePath);
        }

        mDirTypId = dirTypId;
        if (root != null)
            mNavigation = new DirectoryNavigator(root);

        navigateTo(initialAbsolutePath);
    }

    /** reload tree to new newGrandParent by preserving selection */
    private void navigateTo(int newGroupSelection, IDirectory newGrandParent) {
        if (newGrandParent != null) {
            Log.d(TAG, debugPrefix + "navigateTo(" +
                    newGrandParent.getAbsolute() + ")");
            mNavigation.setCurrentGrandFather(newGrandParent);
            this.treeView.setAdapter(mAdapter);
            if (newGroupSelection >= 0) {
                /// find selectedChild as new selectedGroup and expand it
                treeView.expandGroup(newGroupSelection, true);
            }
        }
    }

    /**
     * Set curent selection to absolutePath
     *
     * @param absolutePath
     */
    @Override
    public void navigateTo(String absolutePath) {
        if (Global.debugEnabled) {
            Log.i(Global.LOG_CONTEXT, debugPrefix + " navigateTo : " + absolutePath);
        }

        if ((mNavigation != null) && (absolutePath != null)) {
            mCurrentSelection = mNavigation.getRoot().find(absolutePath);
            mNavigation.navigateTo(mCurrentSelection);
        }

        // does nothing if OnCreate() has not been called yet

        reloadTreeViewIfAvailable();
    }

    /** Does nothing if either OnCreate() or defineDirectoryNavigation() has NOT been called yet */
    private boolean reloadTreeViewIfAvailable() {
        if ((treeView != null) && (mNavigation != null)) {
            if (mAdapter == null) {
                mAdapter = new DirectoryListAdapter(this.mContext,
                        mNavigation, treeView, debugPrefix);
            }
            treeView.setAdapter(mAdapter);

            int g = mNavigation.getLastNavigateToGroupPosition();
            if (g != DirectoryNavigator.UNDEFINED) {
                treeView.expandGroup(g, true);
                updateParentPathBar(mNavigation.getCurrentSelection());
            }

            return true;
        }
        return false;
    }

    /**
     * This interface must be implemented by activities that contain this
     * fragment to allow an interaction in this fragment to be communicated
     * to the activity and potentially other fragments contained in that
     * activity.
     * <p/>
     * See the Android Training lesson <a href=
     * "http://developer.android.com/training/basics/fragments/communicating.html"
     * >Communicating with Other Fragments</a> for more information.
     */
    public interface OnDirectoryInteractionListener {
        /** called when user picks a new directory */
        void onDirectoryPick(String selectedAbsolutePath, int queryTypeId);

        /** called when user cancels picking of a new directory
         * @param queryTypeId*/
        void onDirectoryCancel(int queryTypeId);

        /** called after the selection in tree has changed */
        void onDirectorySelectionChanged(String selectedChild, int queryTypeId);

        /** remove cached directories */
        void invalidateDirectories();
    }
}
