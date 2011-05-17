/*
 * Copyright (C) 2011, Pusztai Tibor <kondi.elte@gmail.com>
 * and other copyright owners as documented in the project's IP log.
 *
 * This program and the accompanying materials are made available
 * under the terms of the Eclipse Distribution License v1.0 which
 * accompanies this distribution, is reproduced below, and is
 * available at http://www.eclipse.org/org/documents/edl-v10.php
 *
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or
 * without modification, are permitted provided that the following
 * conditions are met:
 *
 * - Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * - Redistributions in binary form must reproduce the above
 *   copyright notice, this list of conditions and the following
 *   disclaimer in the documentation and/or other materials provided
 *   with the distribution.
 *
 * - Neither the name of the Eclipse Foundation, Inc. nor the
 *   names of its contributors may be used to endorse or promote
 *   products derived from this software without specific prior
 *   written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND
 * CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES,
 * INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES
 * OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT
 * NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.eclipse.jgit.api;

import java.io.IOException;
import java.text.MessageFormat;

import org.eclipse.jgit.JGitText;
import org.eclipse.jgit.api.errors.ConcurrentRefUpdateException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.api.errors.NoFilepatternException;
import org.eclipse.jgit.api.errors.NoHeadException;
import org.eclipse.jgit.dircache.DirCache;
import org.eclipse.jgit.dircache.DirCacheBuilder;
import org.eclipse.jgit.dircache.DirCacheCheckout;
import org.eclipse.jgit.lib.CommitBuilder;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectInserter;
import org.eclipse.jgit.lib.PersonIdent;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.RefUpdate.Result;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevWalk;

/**
 * Used to stash the changes in a dirty working directory away.
 *
 * @see <a href="http://www.kernel.org/pub/software/scm/git/docs/git-stash.html"
 *      >Git documentation about stash command</a>
 */
public class SaveStashCommand extends GitCommand<ObjectId> {

	private String message;

	private boolean keepIndex;

	/***/
	public String indexCommitName;

	/***/
	public String workingCommitName;

	/**
	 *
	 * @param repo
	 */
	public SaveStashCommand(Repository repo) {
		super(repo);
	}

	/**
	 * Executes the save stash command. Each instance of this class should only
	 * be used for one invocation of the command. Don't call this method twice
	 * on an instance.
	 *
	 * @return the Ref after the stash
	 */
	public ObjectId call() throws IOException, NoHeadException,
			ConcurrentRefUpdateException {
		checkCallable();

		// TODO: check if is there a working directory at all:
		// if (repo.isBare() || repo.getWorkTree() == null)

		// TODO: check if is there any change in the working directory

		String head;
		ObjectId headId = repo.resolve(Constants.HEAD + "^{commit}");
		if (headId != null) {
			RevWalk rw = new RevWalk(repo);
			try {
				RevCommit commit = rw.parseCommit(headId);
				head = headId.abbreviate(7).name() + " "
						+ commit.getShortMessage();
			} finally {
				rw.release();
			}
		} else {
			throw new NoHeadException("No HEAD exists"); // TODO: localize
		}

		try {
			String branch;
			Ref headRef = repo.getRef(Constants.HEAD);
			if (headRef != null) {
				branch = headRef.getTarget().getName()
						.replace("refs/heads/", "");
			} else {
				branch = "(no branch)";
			}

			String msg = branch + ": " + head;

			PersonIdent committer = new PersonIdent(repo);

			ObjectId indexCommitId;

			// lock the index
			DirCache index = repo.lockDirCache();
			try {
				ObjectInserter odi = repo.newObjectInserter();
				try {
					// Write the index as tree to the object database. This may
					// fail for example when the index contains unmerged paths
					// (unresolved conflicts)
					ObjectId indexTreeId = index.writeTree(odi);

					// Create a commit object for index, populate it and write it
					CommitBuilder indexCommit = new CommitBuilder();
					indexCommit.setCommitter(committer);
					indexCommit.setAuthor(committer);
					indexCommit.setMessage("index on " + msg);
					indexCommit.setParentId(headId);
					indexCommit.setTreeId(indexTreeId);
					indexCommitId = odi.insert(indexCommit);
					odi.flush();

					indexCommitName = indexCommitId.name();

					RevWalk revWalk = new RevWalk(repo);
					try {
						RevCommit revCommit = revWalk.parseCommit(headId);
						index.clear();
						DirCacheBuilder dcb = index.builder();
						dcb.addTree(new byte[0], 0, repo.newObjectReader(),
								revCommit.getTree());
						dcb.commit();
					} finally {
						revWalk.release();
					}
				} finally {
					odi.release();
				}
			} finally {
				index.unlock();
			}

			// Add working tree content to index
			Git git = new Git(repo);
			try {
				git.add().addFilepattern(".").setUpdate(true).call();
			} catch (NoFilepatternException e) {
				// should really not happen
				throw new JGitInternalException(e.getMessage(), e);
			}

			ObjectId workingCommitId;

			// lock the index
			index = repo.lockDirCache();
			try {
				ObjectInserter odi = repo.newObjectInserter();
				try {
					// Write the index as tree to the object database. This may
					// fail for example when the index contains unmerged paths
					// (unresolved conflicts)
					ObjectId workingTreeId = index.writeTree(odi);

					// Create a commit object for index, populate it and write
					// it
					CommitBuilder workingCommit = new CommitBuilder();
					workingCommit.setCommitter(committer);
					workingCommit.setAuthor(committer);
					if (message == null) {
						workingCommit.setMessage("WIP on " + msg);
					} else {
						workingCommit.setMessage("On " + branch + ": "
								+ message);
					}
					workingCommit.setParentIds(headId, indexCommitId);
					workingCommit.setTreeId(workingTreeId);
					workingCommitId = odi.insert(workingCommit);
					odi.flush();

					workingCommitName = workingCommitId.name();

					RevWalk revWalk = new RevWalk(repo);
					try {
						RevCommit revCommit = revWalk.parseCommit(headId);
						DirCacheCheckout checkout = new DirCacheCheckout(repo,
								index, revCommit.getTree());
						checkout.setFailOnConflict(false);
						checkout.checkout();
					} finally {
						revWalk.release();
					}
				} finally {
					odi.release();
				}
			} finally {
				index.unlock();
			}

			// TODO: manage keepIndex
			if (keepIndex)
				;

			RevWalk revWalk = new RevWalk(repo);
			try {
				RevCommit revCommit = revWalk.parseCommit(workingCommitId);
				RefUpdate ru = repo.updateRef(Constants.STASH);
				ru.setNewObjectId(workingCommitId);
				ru.setRefLogMessage(revCommit.getShortMessage(), false);

				Result rc = ru.forceUpdate();
				switch (rc) {
				case NEW:
				case FORCED:
				case FAST_FORWARD: {
					setCallable(false);
					return workingCommitId;
				}
				case REJECTED:
				case LOCK_FAILURE:
					throw new ConcurrentRefUpdateException(
							"Could not lock stash", ru.getRef(), rc); // TODO:
																		// localize
				default:
					throw new JGitInternalException(MessageFormat.format(
							JGitText.get().updatingRefFailed, Constants.STASH,
							workingCommitId.toString(), rc));
				}
			} finally {
				revWalk.release();
			}
		} catch (IOException e) {
			throw new JGitInternalException(
					"Exception caught during execution of SaveStashCommand", e); // TODO:
																					// localize
		}
	}

	/**
	 * @param message
	 *            The message gives the description along with the stashed
	 *            state.
	 * @return this instance
	 */
	public SaveStashCommand setMessage(String message) {
		this.message = message;
		return this;
	}

	/**
	 * @param keepIndex
	 *            If the keepIndex is true, all changes already added to the
	 *            index are left intact.
	 * @return this instance
	 */
	public SaveStashCommand setKeepIndex(boolean keepIndex) {
		this.keepIndex = keepIndex;
		return this;
	}

}
