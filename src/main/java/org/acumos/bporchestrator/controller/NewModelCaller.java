/*-
 * ===============LICENSE_START=======================================================
 * Acumos
 * ===================================================================================
 * Copyright (C) 2017 AT&T Intellectual Property & Tech Mahindra. All rights reserved.
 * ===================================================================================
 * This Acumos software file is distributed by AT&T and Tech Mahindra
 * under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *  
 *      http://www.apache.org/licenses/LICENSE-2.0
 *  
 * This file is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * ===============LICENSE_END=========================================================
 */
package org.acumos.bporchestrator.controller;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.acumos.bporchestrator.util.NewThreadAttributes;

public class NewModelCaller implements Runnable {

	private static final Logger ntlogger = LoggerFactory.getLogger(NewModelCaller.class);

	private NewThreadAttributes newThreadAttributes;

	public NewModelCaller(NewThreadAttributes newThreadAttributes) {
		this.newThreadAttributes = newThreadAttributes;
	}

	@Override
	public void run() {

		ntlogger.info("NewModelCaller response thread {} started for {} with input as {}",
				Thread.currentThread().getId(), newThreadAttributes.getsNode(), newThreadAttributes.getOut());

		try {

			new BlueprintOrchestratorController().traverseEachNode(newThreadAttributes.getpNode(),
					newThreadAttributes.getsNode(), newThreadAttributes.getOut(), newThreadAttributes.getId(),
					newThreadAttributes.getProbeCont(), newThreadAttributes.getProbeOp());
		} catch (Exception e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

	}
}