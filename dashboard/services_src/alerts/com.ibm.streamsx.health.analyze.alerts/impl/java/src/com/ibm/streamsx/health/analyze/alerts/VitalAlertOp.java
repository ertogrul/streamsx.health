/* Generated by Streams Studio: August 16, 2017 at 11:56:10 AM EDT */
package com.ibm.streamsx.health.analyze.alerts;


import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.log4j.Logger;
import org.jeasy.rules.api.Facts;
import org.jeasy.rules.api.Rule;
import org.jeasy.rules.api.Rules;
import org.jeasy.rules.api.RulesEngine;
import org.jeasy.rules.core.RulesEngineBuilder;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.ibm.streams.operator.AbstractOperator;
import com.ibm.streams.operator.OperatorContext;
import com.ibm.streams.operator.StreamingData.Punctuation;
import com.ibm.streams.operator.StreamingInput;
import com.ibm.streams.operator.Tuple;
import com.ibm.streams.operator.model.InputPortSet;
import com.ibm.streams.operator.model.InputPortSet.WindowMode;
import com.ibm.streams.operator.model.InputPortSet.WindowPunctuationInputMode;
import com.ibm.streams.operator.model.InputPorts;
import com.ibm.streams.operator.model.Libraries;
import com.ibm.streams.operator.model.OutputPortSet;
import com.ibm.streams.operator.model.OutputPortSet.WindowPunctuationOutputMode;
import com.ibm.streams.operator.model.OutputPorts;
import com.ibm.streams.operator.model.PrimitiveOperator;
import com.ibm.streamsx.health.analyze.alerts.generator.VitalRulesGenerator;
import com.ibm.streamsx.health.analyze.alerts.rules.VitalRule;
import com.ibm.streamsx.health.analyze.alerts.rules.VitalRules;
import com.ibm.streamsx.health.analyze.alerts.rules.VitalsFacts;
import com.ibm.streamsx.health.control.patientcontrolplane.api.PatientControlPlaneContext;

@PrimitiveOperator(name="VitalAlert", namespace="com.ibm.streamsx.health.analyze.alerts",
description="Java Operator VitalAlert")
@InputPorts({@InputPortSet(description="Port that ingests tuples", cardinality=1, optional=false, windowingMode=WindowMode.NonWindowed, windowPunctuationInputMode=WindowPunctuationInputMode.Oblivious)})
@OutputPorts({@OutputPortSet(description="Port that produces tuples", cardinality=1, optional=false, windowPunctuationOutputMode=WindowPunctuationOutputMode.Generating)})
@Libraries({"opt/downloaded/*"})
public class VitalAlertOp extends AbstractOperator {

	private static final String GLOBAL_RULES_PATIENT_ID = "*";
	
	private RulesEngine rulesEngine;
	private Map<String /* patientId */, VitalRules> patientRules;
	private PatientControlPlaneContext pcp;
	
	private Gson gson;
	private AlertManager alertManager;
	
	@Override
	public synchronized void initialize(OperatorContext context)
			throws Exception {
    	// Must call super.initialize(context) to correctly setup an operator.
		super.initialize(context);
		gson = new Gson();
		
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " initializing in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );
        
        patientRules = new ConcurrentHashMap<String, VitalRules>();
        alertManager = new AlertManager(context);
        rulesEngine = RulesEngineBuilder
        		.aNewRulesEngine()
        		.withRuleListener(new VitalsRuleListener(alertManager))
        		.build();

        pcp = new PatientControlPlaneContext(context);
        pcp.addPatientAlertsListener((oldValue, newValue) -> {
        	System.out.println("oldValue=" + oldValue + ", newValue=" + newValue);
        	
        	synchronized(patientRules) {
            	updateRules(newValue);	
        	}
        });
	}

	private void updateRules(String jsonString) {
		if(jsonString == null || jsonString.isEmpty())
			return;
		
		JsonArray jsonArray = gson.fromJson(jsonString, JsonArray.class);
		Map<String /* patientId */, VitalRules> updatedRulesMap = new HashMap<String, VitalRules>();
    	jsonArray.forEach(elem -> {
    		if(elem instanceof JsonObject) {
    			JsonObject jsonObj = (JsonObject)elem;
    			if(jsonObj.has("patientId")) {
    				String patientId = jsonObj.get("patientId").getAsString();
    				if(!patientRules.containsKey(patientId))
    					patientRules.put(patientId, new VitalRules());
    				
    				if(!updatedRulesMap.containsKey(patientId))
    					updatedRulesMap.put(patientId, new VitalRules());
    				
    				updatedRulesMap.get(patientId).registerAll(VitalRulesGenerator.generateRulesFromJson((JsonObject)elem));
    			}
    		}
    	});
    	
    	updatedRulesMap.forEach((patientId, updatedRules) -> {
    		VitalRules previousPatientRules = patientRules.get(patientId);
    		// check if any rules have been removed
    		// iterate over current patient rules and determine 
    		// if any  no longer exist in 'updatedRules'
    		List<Rule> rulesToUnregister = new ArrayList<>();
    		for(Rule rule : previousPatientRules) {
    			if(!updatedRules.contains(rule)) {
    				rulesToUnregister.add(rule);
    				
    				// need to cancel any existing alerts for these rules
    				alertManager.cancelAlert(patientId, (VitalRule)rule, System.currentTimeMillis()/1000, null, "Cancel due to rule being removed.");
    			}
    		}        			
    		rulesToUnregister.forEach(rule -> previousPatientRules.unregister(rule));
    		
    		// add/update existing rules
    		for(Rule rule : updatedRules) {
    			System.out.println("Adding/updating rule: " + rule);
    			if(previousPatientRules.contains(rule)) {
    				previousPatientRules.unregister(rule);
    			}
    			previousPatientRules.register(rule);
    		}
    	});
	}
	
    @Override
    public synchronized void allPortsReady() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " all ports are ready in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );  
    }

    @Override
    public final void process(StreamingInput<Tuple> inputStream, Tuple tuple)
            throws Exception {
    	synchronized(patientRules) {
        	String patientId = tuple.getString("patientId");
        	Rules globalRules = patientRules.get(GLOBAL_RULES_PATIENT_ID);
        	Rules rules = patientRules.get(patientId);
        	
        	// fire the global rules
        	Facts facts = VitalsFacts.newVitalsFacts(patientId, tuple);
        	if(globalRules != null)
        		rulesEngine.fire(globalRules, facts);
        	
        	// fire the patient-specific rules
        	if(rules != null)
        		rulesEngine.fire(rules, facts);
    	}
    }
    
	@Override
    public void processPunctuation(StreamingInput<Tuple> stream,
    		Punctuation mark) throws Exception {
    	// For window markers, punctuate all output ports 
    	super.processPunctuation(stream, mark);
    }

    public synchronized void shutdown() throws Exception {
        OperatorContext context = getOperatorContext();
        Logger.getLogger(this.getClass()).trace("Operator " + context.getName() + " shutting down in PE: " + context.getPE().getPEId() + " in Job: " + context.getPE().getJobId() );

        // Must call super.shutdown()
        super.shutdown();
    }
}