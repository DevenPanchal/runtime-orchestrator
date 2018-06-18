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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import org.acumos.bporchestrator.controller.DBResponseRunnable;
import org.acumos.bporchestrator.MCAttributes;
import org.acumos.bporchestrator.collator.service.ProtobufService;
import org.acumos.bporchestrator.collator.service.ProtobufServiceImpl;
import org.acumos.bporchestrator.collator.vo.Configuration;
import org.acumos.bporchestrator.model.*;
import org.acumos.bporchestrator.util.TaskManager;
import org.apache.commons.io.IOUtils;
import org.apache.http.client.utils.URIBuilder;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.swagger.annotations.ApiOperation;
import io.swagger.annotations.ApiParam;
import org.acumos.bporchestrator.controller.FinalResults;

/**
 * Rest Controller that handles the API end points
 */
@RestController
public class BlueprintOrchestratorController {

	private static final Logger logger = LoggerFactory.getLogger(BlueprintOrchestratorController.class);
	private static final String DATABROKER = "Databroker";
	@Autowired
	@Qualifier("ProtobufServiceImpl")
	private ProtobufService protoService;

	/**
	 * 
	 * @param binaryStream
	 *            This is the binary stream from the data source
	 * @param operation
	 *            This specifies the input_operation_signature of the runtime
	 *            orchestrator should invoke on the first node based on
	 *            blueprint.json
	 * @return Byte stream
	 */

	// The MC is triggerred by a /{operation} request. The requester also sends the
	// protobuf binary message.

	@ApiOperation(value = "operation on the first node in the chain", response = byte.class, responseContainer = "Page")
	@RequestMapping(path = "/{operation}", method = RequestMethod.POST)
	public ResponseEntity<byte[]> notify(
			@ApiParam(value = "Inital request to start deploying... This binary stream is in protobuf format.", required = false) @Valid @RequestBody byte[] binaryStream,
			@ApiParam(value = "This operation should match with one of the input operation signatures in blueprint.json", required = true) @PathVariable("operation") String operation) {

		byte[] finalresults = null;

		Blueprint blueprint = TaskManager.getBlueprint();
		DockerInfoList dockerList = TaskManager.getDockerList();
		;

		// Probe related.
		boolean probePresent = false;

		String probeContName = "Probe";
		String probeOperation = "data";
		String probeurl = null;

		// Input node related
		String inpcontainer = null;
		String inpoperation = null;

		// On receiving this request, the data source is blocked.
		// We will need to unblock it only when we receive output from the first model.

		// Check if blueprint and dockerList has been populated.
		try {
			logger.info("****************************************************************************");
			logger.info("notify: Receiving /{} request: {}", operation,
					(Arrays.toString(binaryStream)).substring(0, 20));
			if (blueprint == null) {
				logger.error("notify: Empty blueprint JSON");
				return new ResponseEntity<>(finalresults, HttpStatus.PARTIAL_CONTENT);

			}
			if (dockerList == null) {
				logger.error("notify: Need Docker Information... Exiting");
				return new ResponseEntity<>(finalresults, HttpStatus.PARTIAL_CONTENT);
			}

			List<InputPort> inps = blueprint.getInputPorts();
			byte[] output = null;

			// Check if Probe is present in the composite solution. If yes, get its
			// container name, and operation. THIS PROBE DATA WILL BE USED FOR THE DATA
			// SOURCE CASE ONLY.

			ArrayList<ProbeIndicator> list_of_pb_indicators = blueprint.getProbeIndicator();
			for (ProbeIndicator pbindicator : list_of_pb_indicators) {
				if (pbindicator.getValue().equalsIgnoreCase("true")) {
					probePresent = true;
				}
			}

			// Find the url etc. for the probe
			if (probePresent == true) {
				DockerInfo d2 = dockerList.findDockerInfoByContainer(probeContName);

				if (d2 == null) { // what to do if the deployer passed
					// incomplete docker info ???
					logger.error("notify: Cannot find docker info about the probe {}", probeContName);
					return new ResponseEntity<>(finalresults, HttpStatus.INTERNAL_SERVER_ERROR);
				}

				URIBuilder builder = new URIBuilder();
				builder.setScheme("http").setHost(d2.getIpAddress()).setPort(new Integer(d2.getPort()).intValue())
						.setPath("/" + probeOperation);

				probeurl = builder.build().toURL().toString();
			}

			// Now call the input node and its operation signature.
			for (InputPort inport : inps) {
				if (inport.getOperationSignature().getOperationName().equals(operation)) {
					inpcontainer = inport.getContainerName();
					inpoperation = operation;
					break;

				}
			}

			String inpmsgname = null;

			Node inpnode = blueprint.getNodebyContainer(inpcontainer);
			ArrayList<OperationSignatureList> inplosl = inpnode.getOperationSignatureList();
			for (OperationSignatureList inposl : inplosl) {
				inpmsgname = inposl.getOperationSignature().getInputMessageName();
				break;
			}

			DockerInfo d2 = dockerList.findDockerInfoByContainer(inpcontainer);

			if (d2 == null) { // what to do if the deployer passed
				// incomplete docker info ???
				logger.error("notify: Cannot find docker info about the dontainer {}", inpcontainer);
				return new ResponseEntity<>(finalresults, HttpStatus.INTERNAL_SERVER_ERROR);
			}

			URIBuilder builder = new URIBuilder();
			builder.setScheme("http").setHost(d2.getIpAddress()).setPort(new Integer(d2.getPort()).intValue())
					.setPath("/" + inpoperation);

			String inpurl = builder.build().toURL().toString();
			output = contactnode(binaryStream, inpurl, inpcontainer);

			/*
			 * if probe in composite solution send the input message of input node to probe
			 * also and wait for http }
			 */
			if (probePresent == true) {

				// create a dummydepsofdeps null object
				ArrayList<ConnectedTo> dummydepsofdeps = null;
				prepareAndContactprobe(inpoperation, probeurl, inpcontainer, binaryStream, output, dummydepsofdeps);

				/*
				 * logger
				 * info("notify: Notifying PROBE for node name: {}, inp msg name: {} , msg: {}",
				 * inpnode.getContainerName(), inpmsgname,
				 * Arrays.toString(binaryStream).substring(0, 20));
				 * 
				 * byte[] out = contactProbe(binaryStream, probeurl, probeContName, inpmsgname,
				 * inpnode);
				 */
			}

			notifyNextNode(output, inpnode, inpoperation, probePresent, probeContName, probeOperation, probeurl);
			FinalResults f = TaskManager.getFinalResults();
			finalresults = f.getFinalresults();
		} // try ends for notify method's chunk
		catch (Exception ex) {
			logger.error("notify: Notify failed {}", ex);
			return new ResponseEntity<>(finalresults, HttpStatus.INTERNAL_SERVER_ERROR);

		}
		return new ResponseEntity<>(finalresults, HttpStatus.OK);
	}

	/**
	 * @param output
	 *            The result of the previous node
	 * @param n
	 *            Current node
	 * @param oprn
	 *            Current node's operation which was called
	 * @param prbpresent
	 *            Indicator of probe presence in the blueprint
	 * @param prbcontainername
	 *            Probe's container name if Probe is present
	 * @param prboperation
	 *            Probe's operation name if Probe is present
	 * @param prburl
	 *            Probe's url if Probe is present
	 * @return byte[] output stream
	 * @throws Exception
	 *             : Exception
	 */
	public byte[] notifyNextNode(byte[] output, Node n, String oprn, boolean prbpresent, String prbcontainername,
			String prboperation, String prburl) throws Exception {

		Blueprint blueprint = TaskManager.getBlueprint();
		DockerInfoList dockerList = TaskManager.getDockerList();

		// Find dependents of the node. If the dependent is null, then send the output
		// message to the probe and stop looping.
		ArrayList<ConnectedTo> deps = null;
		ArrayList<ConnectedTo> depsofdeps = null;
		try {
			deps = findconnectedto(n, oprn);
		} catch (Exception e) {
			logger.error("notifyNextNode: Finding Connected to failed {}", e);
		}

		// First time the loop executes (i.e when we call 1st dependent node, the value
		// of depoutput is output from previous databroker or input node.
		// Later on on every call it will be the output of the contactnode call passed
		// as depoutput2)
		byte[] depoutput = output;
		byte[] depoutput2 = null;

		// for loop here to loop through the dependents.
		for (ConnectedTo depitem : deps) {

			String depcontainername = depitem.getContainerName();
			String depoperation = depitem.getOperationSignature().getOperationName();
			Node depnode = blueprint.getNodebyContainer(depcontainername);

			// Find dependent's url and name for the node. Url finding will also need the
			// dependent's operation
			DockerInfo d3 = dockerList.findDockerInfoByContainer(depcontainername);

			if (d3 == null && !(depcontainername.contains("Splitter") || depcontainername.contains("Collator"))) {
				// what to do if the deployer passed incomplete dockerInfo ???
				logger.error("Cannot find docker info about the container {}", depcontainername);
			}

			// Contact the dependent node.
			if (!(((depnode.getNodeType()).equals("Splitter")) || ((depnode.getNodeType()).equals("Collator")))) {

				URIBuilder builder = new URIBuilder();
				builder.setScheme("http").setHost(d3.getIpAddress()).setPort(new Integer(d3.getPort()).intValue())
						.setPath("/" + depoperation);

				String depurl = builder.build().toURL().toString();
				depoutput2 = contactnode(depoutput, depurl, depcontainername);
			}

			// find dependents of the current dependent to see if we need to send input msg
			// or both the input and output msg.
			try {
				depsofdeps = findconnectedto(depnode, depoperation);

				if ((depnode.getNodeType()).equals("Splitter")) {
					List<byte[]> mergedoutput;

					// CALLING MULTIPLE MODELS WITH SPLITTER INPUT i.e SPLITTER's PRECEDING NODE's
					// OUTPUT.
					// THIS NEEDS TO CHANGE IN PARAMETER BASED SPLITTER WHERE ITS INPUT AND OUTPUT
					// WILL NOT BE THE SAME.

					// CALL MULTIPLE MODELS SEQUENTIALLY
					// mergedoutput = callMultipleModels(depoutput, depsofdeps, prburl);

					// CALL MULTIPLE MODELS PARALLELY
					mergedoutput = callMultipleModelsParallelyClient(depoutput, depsofdeps, prburl);

					// whether splitter has collator reference or not
					depsofdeps = linkRelatedCollator(depsofdeps);

					// configure collator here
					String collatorcontainer = depsofdeps.get(0).getContainerName();
					Node collatornode = blueprint.getNodebyContainer(collatorcontainer);
					String containeroperation = collatornode.getOperationSignatureList().get(0).getOperationSignature()
							.getOperationName();
					String collatortype = collatornode.getCollatorMap().getCollatorType();
					String protobufFileStr = collatornode.getCollatorMap().getOutputMessageSignature();

					Configuration configuration = new Configuration();
					configuration.setCollator_type(collatortype);
					configuration.setProtobufFileStr(protobufFileStr);
					protoService.setConf(configuration);
					protoService.processProtobuf();

					// call collator
					// ProtobufService protobufService1 = new ProtobufServiceImpl();

					// REAL CALL
					/*
					 * try { depoutput2 = protoService.collateData(mergedoutput); } catch (Exception
					 * e) { logger.error("calling collateData method of the Collator failed{}", e);
					 * }
					 */

					// SIMULATED CALL
					depoutput2 = httpPost("http://127.0.0.1:9222/collate", output);

					// Set depnode to collator node.
					depnode = collatornode;
					depoperation = containeroperation;
					depcontainername = collatorcontainer;

				}

			}

			catch (Exception e1) {
				logger.error("notifyNextNode: Finding connected to failed {}", e1);
			}

			if (prbpresent == true
					&& !(((depcontainername.contains("Splitter")) || (depcontainername.contains("Collator"))))) {

				// For any node (if probe is present), contact probe with input message
				// For the last node(if probe is present), contact probe with input message name
				// & contact probe with output message name

				prepareAndContactprobe(depoperation, prburl, depcontainername, depoutput, depoutput2, depsofdeps);

			}

			if (depsofdeps.isEmpty()) {
				FinalResults f2 = new FinalResults(null);
				f2.setFinalresults(depoutput2);
				TaskManager.setFinalResults(f2);
			}

			// Call notifyNextNode function
			if (!(depsofdeps.isEmpty())) {
				notifyNextNode(depoutput2, depnode, depoperation, prbpresent, prbcontainername, prboperation, prburl);

			}
		}
		return output;
	}

	/**
	 * Contacting probe
	 * 
	 * @param probeurl
	 *            : url of the probe
	 * @param binaryStream
	 *            : input protobuf binary message.
	 * @param probeurl
	 *            : url of probe
	 * @param msg_name
	 *            : input message name
	 * @param n
	 *            : the node object
	 * @param pb_c_name
	 *            : probe container name
	 *
	 * @return : returns byte[] type
	 * @throws IOException
	 *             : IO exception
	 */

	public byte[] contactProbe(byte[] binaryStream, String probeurl, String pb_c_name, String msg_name, Node n)
			throws Exception {

		Blueprint blueprint = TaskManager.getBlueprint();
		DockerInfoList dockerList = TaskManager.getDockerList();

		byte[] output2;
		Node probeNode = blueprint.getNodebyContainer(pb_c_name);
		logger.info("contactProbe: Thread {} : Notifying probe: {}, POST: {}, proto uri: {} , message Name: {}",
				Thread.currentThread().getId(), pb_c_name, probeurl, n.getProtoUri(), msg_name);
		// logger.info("Thread " + Thread.currentThread().getId() + ": " + "Notifying
		// probe " + pb_c_name + " POST: "
		// + probeurl + " proto uri = [" + n.getProtoUri() + "] message Name = [" +
		// "Sample message" + "]");
		output2 = httpPost(probeurl, binaryStream, n.getProtoUri(), msg_name);
		return output2;
	}

	void prepareAndContactprobe(String depoperation, String prburl, String depcontainername, byte[] depoutput,
			byte[] depoutput2, ArrayList<ConnectedTo> depsofdeps) {

		// Call notifyprobe if indicator is true.
		// Message name needed for sending to probe. (Find the input message name for
		// the node. This will be needed later if probe is present.)

		Blueprint blueprint = TaskManager.getBlueprint();
		DockerInfoList dockerInfoList = TaskManager.getDockerList();
		String depinpmsgname = null;
		String depoutmsgname = null;
		byte[] probeout = null;
		byte[] probeout2 = null;
		Node depnode = blueprint.getNodebyContainer(depcontainername);
		ArrayList<OperationSignatureList> deplosl = depnode.getOperationSignatureList();
		for (OperationSignatureList deposl : deplosl) {
			// checking if the operation is the same as requested operation. Only then
			// proceed to set the depinpmsgname and depoutmsgname accordingly.
			if ((deposl.getOperationSignature().getOperationName()).equals(depoperation))

			{
				depinpmsgname = deposl.getOperationSignature().getInputMessageName();
				depoutmsgname = deposl.getOperationSignature().getOutputMessageName();
				break;
			}

		}

		// If probe is present- For first node (with dummydepsofdeps = null) or any node
		// with depsofdeps not empty,
		// contact probe with input message

		if ((depsofdeps == null) || !(depsofdeps.isEmpty())) {
			try {
				logger.info("notifyNextNode: Thread {} : Notifying PROBE for node name: {}, inp msg name: {} , msg: {}",
						Thread.currentThread().getId(), depcontainername, depinpmsgname,
						(Arrays.toString(depoutput)).substring(0, 20));

				probeout = contactProbe(depoutput, prburl, depcontainername, depinpmsgname, depnode);
			} catch (Exception e) {
				logger.error("Contacting probe failed {}", e);
			}
		}

		// If probe is present -For the last node contact probe with input message name
		// & contact probe with output message name
		if ((depsofdeps != null) && (depsofdeps.isEmpty())) {

			try {

				logger.info("notifyNextNode: Thread {} : Notifying PROBE for node name: {}, inp msg name: {} , msg: {}",
						Thread.currentThread().getId(), depcontainername, depinpmsgname,
						(Arrays.toString(depoutput)).substring(0, 20));

				probeout = contactProbe(depoutput, prburl, depcontainername, depinpmsgname, depnode);
			} catch (Exception e) {
				logger.error("notifyNextNode: Contacting probe failed {}", e);
			}
			try {
				// Note here depoutput2 is assigned to local depoutputof this function.
				logger.info("notifyNextNode: Thread {} : Notifying PROBE for node name: {}, out msg name: {} , msg: {}",
						Thread.currentThread().getId(), depcontainername, depoutmsgname,
						(Arrays.toString(depoutput2)).substring(0, 20));

				probeout2 = contactProbe(depoutput2, prburl, depcontainername, depoutmsgname, depnode);
			} catch (Exception e) {
				logger.error("notifyNextNode: Contacting probe failed - ", e);
			}

		}

	}

	/**
	 * Contacting a node
	 * 
	 * @param binaryStream
	 *            : binary message
	 * @param url
	 *            : url of the data broker
	 * @param name
	 *            : url of the databroker
	 * @return byte[] : received protobuf message
	 */

	public byte[] contactnode(byte[] binaryStream, String url, String name) {

		logger.info("contactnode: Thread {} : Notifying node: {}, POST: {}", Thread.currentThread().getId(), name, url);

		byte[] output4 = null;
		try {
			output4 = httpPost(url, binaryStream);
		} catch (Exception e) {
			logger.error("contactnode: Contacting node failed - ", e);
		}
		return output4;
	}

	/**
	 * Contacting data broker
	 * 
	 * @param db_url
	 *            : url of the data broker
	 * @param db_c_name
	 *            : url of the databroker
	 * @return byte[] : received protobuf message
	 * @throws Exception
	 *             : Exception
	 */

	public byte[] contactDataBroker(String db_url, String db_c_name) throws Exception {

		// Find the databroker script
		Blueprint blueprint = TaskManager.getBlueprint();
		Node dbnode = blueprint.getNodebyContainer(db_c_name);

		logger.info("contactDataBroker: Thread {} : Notifying databroker: {}, POST: {}", Thread.currentThread().getId(),
				db_c_name, db_url);

		byte[] output3 = null;

		output3 = httpGet(db_url);

		// Print out partial output from Data Broker
		logger.info("contactDataBroker: Thread " + Thread.currentThread().getId() + " : " + output3.length
				+ " bytes read from DataBroker");

		return output3;
	}

	/**
	 * Find connectedTo given a Node and its OperationSgnatureList data structure.
	 * 
	 * @param n
	 *            : Node
	 * @param sent_ops
	 *            : Operation name.
	 * @return : returns byte[] type
	 *
	 * @throws Exception
	 *             : Exception
	 */

	public ArrayList<ConnectedTo> findconnectedto(Node n, String sent_ops) throws Exception {

		ArrayList<OperationSignatureList> listOfOperationSigList = n.getOperationSignatureList();
		for (OperationSignatureList opsl2 : listOfOperationSigList) {
			if ((opsl2.getOperationSignature().getOperationName()).equals(sent_ops)) {
				return opsl2.getConnectedTo();

			}
		}
		return null; // there will never be an occasion where we will return null. Even in the case
		// where no dependents, the Array list of type connectedTo will be returned by
		// the inner return. But in this case, the Arraylist will be empty and have no
		// connectedTo objects.
	}

	/**
	 * This is the end point for the deployer to push blueprint.json
	 * 
	 * @param blueprintReq
	 *            Holds blueprint.json
	 * @return HttpStatus.OK
	 */
	@ApiOperation(value = "Endpoint for the deployer to put blueprint JSON", response = Map.class, responseContainer = "Page")
	@RequestMapping(value = "/putBlueprint", consumes = { "application/json" }, method = RequestMethod.PUT)
	public ResponseEntity<Map<String, String>> putBlueprint(
			@ApiParam(value = "Blueprint JSON", required = true) @Valid @RequestBody Blueprint blueprintReq) {
		logger.info("****************************************************************************");
		logger.info("Receiving /putBlueprint request: {}", blueprintReq.toString());

		TaskManager.setBlueprint(blueprintReq);
		Blueprint blueprint = TaskManager.getBlueprint();

		logger.info("putBluePrint: Returning HttpStatus.OK from putBlueprint");
		Map<String, String> results = new LinkedHashMap<>();
		results.put("status", "ok");
		return new ResponseEntity<>(results, HttpStatus.OK);
	}

	/**
	 * The end point for the deployer to push docker info.
	 * 
	 * @param dockerListReq
	 *            Holds dockerInfo.json, containing IP addresses and port numbers
	 *            about all containers
	 * @return HttpStatus.OK
	 */
	@ApiOperation(value = "Endpoint for the deployer to push docker Info JSON", response = Map.class, responseContainer = "Page")
	@RequestMapping(value = "/putDockerInfo", consumes = { "application/json" }, method = RequestMethod.PUT)
	public ResponseEntity<Map<String, String>> putDockerInfo(
			@ApiParam(value = "Docker Info JSON", required = true) @Valid @RequestBody DockerInfoList dockerListReq) {
		logger.info("****************************************************************************");
		logger.info("Receiving /putDockerInfo request: {}", dockerListReq.toString());

		Map<String, String> dbresults = new LinkedHashMap<>();
		TaskManager.setDockerList(dockerListReq);

		try {

			Blueprint blueprint = TaskManager.getBlueprint();
			DockerInfoList dockerList = TaskManager.getDockerList();

			// Check if blueprint and dockerList has been populated.

			if (blueprint == null) {
				logger.error("putDockerInfo: Empty blueprint JSON");
				return new ResponseEntity<>(dbresults, HttpStatus.PARTIAL_CONTENT);
			}
			if (dockerList == null) {
				logger.error("putDockerInfo: Need Docker Information... Exiting");
				return new ResponseEntity<>(dbresults, HttpStatus.PARTIAL_CONTENT);
			}

			List<Node> allnodes = blueprint.getNodes();

			// DataBroker related.
			boolean dataBrokerPresent = false;
			String dataBrokerContName = null;
			ArrayList<OperationSignatureList> ListOfDataBrokerOpSigList = null;
			String dataBrokerOperation = null;

			// Probe related.
			boolean probePresent = false;
			String probeContName = "Probe";
			String probeOperation = "data";
			String probeurl = null;

			// Check if data broker is present in the composite solution. If yes, get its
			// container name, and operation.
			for (Node nd : allnodes) {
				if (nd.getNodeType().equalsIgnoreCase(DATABROKER)) {
					dataBrokerPresent = true;
					// get its container name : to be used later.
					dataBrokerContName = nd.getContainerName();
					// getting its operation name : to be used later ...OR is it always get_image?
					ListOfDataBrokerOpSigList = nd.getOperationSignatureList();
					for (OperationSignatureList dbosl : ListOfDataBrokerOpSigList) {
						dataBrokerOperation = dbosl.getOperationSignature().getOperationName(); // here we are assuming
						// Databroker will have only 1 operation. this can be changed.
					}
					break;
				}
			}

			String databrokerurl = null;
			byte[] output = null;

			// Find the url etc. for this data broker.
			if (dataBrokerPresent == true) {
				DockerInfo d1 = dockerList.findDockerInfoByContainer(dataBrokerContName);

				if (d1 == null) { // what to do if the deployer passed
					// incomplete docker info ???
					logger.error("putDockerInfo: Cannot find docker info about the data broker {}", dataBrokerContName);
					dbresults.put("status", "error");
					return new ResponseEntity<>(dbresults, HttpStatus.INTERNAL_SERVER_ERROR);
				}

				URIBuilder builder = new URIBuilder();
				builder.setScheme("http").setHost(d1.getIpAddress()).setPort(new Integer(d1.getPort()).intValue())
						.setPath("/" + dataBrokerOperation); // Is this always get_image?

				databrokerurl = builder.build().toURL().toString();
			}

			// Check if Probe is present in the composite solution. If yes, get its
			// container name, and operation. THIS PROBE DATA WILL BE USED FOR THE DATA
			// BROKER CASE ONLY. // Checking here again because requests back
			// to back where taking old probe related values when probe related values were
			// made class fields.

			// IN CASE OF DATA SOURCE, call to /putDockerInfo will initialize the incorrect
			// values. Then the
			// notify function will initialize the correct values.

			ArrayList<ProbeIndicator> list_of_pb_indicators = blueprint.getProbeIndicator();
			for (ProbeIndicator pbindicator : list_of_pb_indicators) {
				if (pbindicator.getValue().equalsIgnoreCase("true")) {
					probePresent = true;
				}
			}

			// Find the url etc. for the probe
			if (probePresent == true) {
				DockerInfo d2 = dockerList.findDockerInfoByContainer(probeContName);

				if (d2 == null) { // what to do if the deployer passed
					// incomplete docker info ???
					logger.error("putDockerInfo: Cannot find docker info about the probe {}", probeContName);
					return new ResponseEntity<>(dbresults, HttpStatus.INTERNAL_SERVER_ERROR);
				}

				URIBuilder builder = new URIBuilder();
				builder.setScheme("http").setHost(d2.getIpAddress()).setPort(new Integer(d2.getPort()).intValue())
						.setPath("/" + probeOperation);

				probeurl = builder.build().toURL().toString();
			}

			/*
			 * if Databroker in composite solution { make a POST request to data broker
			 * which will send the message. This is your message}
			 */

			Node dbnode = blueprint.getNodebyContainer(dataBrokerContName);
			if (dataBrokerPresent == true) {
				ExecutorService service = Executors.newFixedThreadPool(1);
				// make a GET request to the databroker and receive response
				do {
					output = contactDataBroker(databrokerurl, dataBrokerContName);
					if ((output != null) && (output.length != 0)) {
						MCAttributes mcAttributes = new MCAttributes();
						// set the all the required attributes.
						mcAttributes.setOutput(output);
						mcAttributes.setCurrentNode(dbnode);
						mcAttributes.setCurrentOperation(dataBrokerOperation);
						mcAttributes.setProbePresent(probePresent);
						mcAttributes.setProbeContName(probeContName);
						mcAttributes.setProbeOperation(probeOperation);
						mcAttributes.setProbeUrl(probeurl);
						// create a thread with the mcAttribute objects
						service.execute(new DBResponseRunnable(mcAttributes));
					}

				} while ((output != null) && (output.length != 0));
			}
			dbresults.put("status", "ok");
			logger.info("putDockerInfo: Thread {} : Returning HttpStatus.OK from putDockerInfo",
					Thread.currentThread().getId());
			return new ResponseEntity<>(dbresults, HttpStatus.OK);
		} catch (Exception ex) {
			logger.error("putDockerInfo(): failed to put dockerInfo: " + ex);
			return new ResponseEntity<>(dbresults, HttpStatus.INTERNAL_SERVER_ERROR);
		}

	}

	private List<byte[]> callMultipleModels(byte[] depoutput, ArrayList<ConnectedTo> depsofdeps, String prburl) {
		List<byte[]> listOfbyte = new ArrayList<byte[]>();
		DockerInfoList dockerInfoList = TaskManager.getDockerList();
		for (ConnectedTo connectedNode : depsofdeps) {
			String depcontainername = connectedNode.getContainerName();
			DockerInfo dockerInfo = dockerInfoList.findDockerInfoByContainer(depcontainername);

			// Call the models/connectedNode
			byte[] modelOutput = contactnode(depoutput, "http://" + dockerInfo.getIpAddress() + ":"
					+ dockerInfo.getPort() + "/" + connectedNode.getOperationSignature().getOperationName(),
					dockerInfo.getContainer());
			String depoperation = connectedNode.getOperationSignature().getOperationName();

			// Contact the probe for the model /connectedNode
			prepareAndContactprobe(depoperation, prburl, depcontainername, depoutput, modelOutput, depsofdeps);

			// call to probe ends
			listOfbyte.add(modelOutput);
		}
		return listOfbyte;
	}

	private List<byte[]> callMultipleModelsParallelyClient(byte[] depoutput, ArrayList<ConnectedTo> depsofdeps,
			String prburl) {
		ExecutorService executor = Executors.newFixedThreadPool(4);
		List<Future<byte[]>> list = new ArrayList<Future<byte[]>>();
		DockerInfoList dockerInfoList = TaskManager.getDockerList();
		List<Future<byte[]>> listOfMlOutput = new ArrayList<Future<byte[]>>();
		List<byte[]> listOfbyte = new ArrayList<byte[]>();
		for (ConnectedTo connectedNode : depsofdeps) {
			String depcontainername = connectedNode.getContainerName();
			String depoperation = connectedNode.getOperationSignature().getOperationName();
			DockerInfo dockerInfo = dockerInfoList.findDockerInfoByContainer(depcontainername);
			Callable<byte[]> callable = new CallMultipleModelParallely(depoutput, depcontainername, depoperation,
					prburl, depsofdeps);
			Future<byte[]> future = executor.submit(callable);

			listOfMlOutput.add(future);
		}
		for (Future<byte[]> fut : listOfMlOutput) {
			try {
				listOfbyte.add(fut.get());
				System.out.println(new Date() + "::" + fut.get());
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
		return listOfbyte;
	}

	private ArrayList<ConnectedTo> linkRelatedCollator(ArrayList<ConnectedTo> connectedNodeList) {
		Blueprint blueprint = TaskManager.getBlueprint();
		for (ConnectedTo connectedNode : connectedNodeList) {
			String depcontainername = connectedNode.getContainerName();
			Node depnode = blueprint.getNodebyContainer(depcontainername);
			ArrayList<OperationSignatureList> listOfOperationSigList = depnode.getOperationSignatureList();
			ArrayList<ConnectedTo> connectedTo = listOfOperationSigList.get(0).getConnectedTo();
			String container_name = connectedTo.get(0).getContainerName();
			if (container_name.contains("Collator")) {

				return connectedTo;
			} else {
				return null;
			}
		}
		return null;
	}

	/**
	 * Sending HTTP POST request to Models
	 * 
	 * @param url
	 *            : url to post to
	 * @param binaryStream
	 *            : the binary message
	 * @return: returns byte[]
	 * @throws IOException
	 *             : IO exception
	 */
	private byte[] httpPost(String url, byte[] binaryStream) throws Exception {
		return httpPost(url, binaryStream, null, null);
	}

	/**
	 * Sending HTTP POST request to Probe
	 * 
	 * @param url
	 *            : url of the node
	 * @param binaryStream
	 *            : the binary message
	 * @param protoUrl
	 *            : probe specific data
	 * @param messageName
	 *            : probe specific data
	 * @return: returns byte[] type
	 * @throws IOException
	 *             : IOException
	 */
	private byte[] httpPost(String url, byte[] binaryStream, String protoUrl, String messageName) throws Exception {

		URL obj = new URL(url);

		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		con.setRequestMethod("POST");
		con.setRequestProperty("Content-Type", "application/octet-stream;");
		if (protoUrl != null)
			con.setRequestProperty("PROTO-URL", protoUrl);
		if (messageName != null)
			con.setRequestProperty("Message-Name", messageName);
		con.setDoOutput(true);
		OutputStream out = con.getOutputStream();
		out.write(binaryStream);
		out.flush();
		out.close();
		logger.info("httpPost: Thread {}: HTTPS POST Sent {}", Thread.currentThread().getId(), url);
		String responseMessage = con.getResponseMessage();
		logger.info("httpPost: Thread {}: GOT RESPONSE Message {}", Thread.currentThread().getId(), responseMessage);
		int responseCode = con.getResponseCode();
		logger.info("httpPost: Thread {}: GOT RESPONSE CODE {}", Thread.currentThread().getId(), responseCode);
		if ((responseCode == HttpURLConnection.HTTP_OK) || (responseCode == HttpURLConnection.HTTP_CREATED)
				|| (responseCode == HttpURLConnection.HTTP_ACCEPTED)
				|| (responseCode == HttpURLConnection.HTTP_ACCEPTED)
				|| (responseCode == HttpURLConnection.HTTP_NOT_AUTHORITATIVE)
				|| (responseCode == HttpURLConnection.HTTP_NO_CONTENT) || (responseCode == HttpURLConnection.HTTP_RESET)
				|| (responseCode == HttpURLConnection.HTTP_PARTIAL)) {
			return IOUtils.toByteArray(con.getInputStream());
		} else {
			logger.error("httpPost: Thread {}: ERROR:::::::POST request did not work {}",
					Thread.currentThread().getId(), url);
		}

		return new byte[0];

	}

	/**
	 * Sending HTTP POST request to the Data Broker
	 * 
	 * @param url
	 *            : url to get
	 * @param theScript
	 *            : actual script to be sent to the data broker.
	 * @return : returns byte[] type
	 * @throws IOException
	 *             : IO exception
	 */
	public byte[] httpPost(String url, String theScript) throws IOException {
		URL obj = new URL(url);

		HttpURLConnection con = (HttpURLConnection) obj.openConnection();

		con.setRequestMethod("POST");

		/*
		 * Write String to the connection
		 * https://docs.oracle.com/javase/tutorial/networking/urls/readingWriting.html
		 */
		con.setDoOutput(true);
		OutputStreamWriter out = new OutputStreamWriter(con.getOutputStream());
		out.write(theScript);
		out.close();

		logger.info("httpPost: Thread {}: HTTPS POST Sent {}", Thread.currentThread().getId(), url);
		String responseMessage = con.getResponseMessage();
		logger.info("httpPost: Thread {}: GOT RESPONSE Message {}", Thread.currentThread().getId(), responseMessage);
		int responseCode = con.getResponseCode();
		logger.info("httpPost: Thread {}: GOT RESPONSE CODE {}", Thread.currentThread().getId(), responseCode);
		if ((responseCode == HttpURLConnection.HTTP_OK) || (responseCode == HttpURLConnection.HTTP_CREATED)
				|| (responseCode == HttpURLConnection.HTTP_ACCEPTED)
				|| (responseCode == HttpURLConnection.HTTP_ACCEPTED)
				|| (responseCode == HttpURLConnection.HTTP_NOT_AUTHORITATIVE)
				|| (responseCode == HttpURLConnection.HTTP_NO_CONTENT) || (responseCode == HttpURLConnection.HTTP_RESET)
				|| (responseCode == HttpURLConnection.HTTP_PARTIAL)) {
			return IOUtils.toByteArray(con.getInputStream());
		} else {
			logger.error("httpPost: Thread {}: ERROR:::::::POST request did not work {}",
					Thread.currentThread().getId(), url);
		}

		return new byte[0];
	}

	/*
	 * This reads from HTTPURLConnection into the memory then return the contents of
	 * the memory.
	 */
	private byte[] httpGet(String url) throws Exception {
		HttpURLConnection con = null;
		InputStream in = null;
		ByteArrayOutputStream out = new ByteArrayOutputStream(8192);
		int count;
		try {
			URL obj = new URL(url);
			con = (HttpURLConnection) obj.openConnection();

			con.setRequestMethod("GET");
			int responseCode = con.getResponseCode();
			logger.info("httpGet(): GET Response Code ::" + responseCode);
			if (responseCode == HttpURLConnection.HTTP_OK) { // success
				in = con.getInputStream();
				byte[] buffer = new byte[8192];
				while ((count = in.read(buffer)) > 0)
					out.write(buffer, 0, count);

				return out.toByteArray();
			} else {
				logger.error("httpGet: Thread {}: ERROR:::::::GET request did not work {}",
						Thread.currentThread().getId(), url);
				throw new Exception("GET " + url + " request did not work");
			}
		} finally {
			if (in != null)
				in.close();

			out.close();
		}
	}

}
