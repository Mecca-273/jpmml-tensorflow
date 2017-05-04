/*
 * Copyright (c) 2017 Villu Ruusmann
 *
 * This file is part of JPMML-TensorFlow
 *
 * JPMML-TensorFlow is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * JPMML-TensorFlow is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with JPMML-TensorFlow.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jpmml.tensorflow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.dmg.pmml.DataField;
import org.dmg.pmml.DataType;
import org.dmg.pmml.FieldName;
import org.dmg.pmml.MiningFunction;
import org.dmg.pmml.OpType;
import org.dmg.pmml.regression.RegressionModel;
import org.jpmml.converter.ContinuousLabel;
import org.jpmml.converter.Feature;
import org.jpmml.converter.Label;
import org.jpmml.converter.ModelUtil;
import org.jpmml.converter.Schema;
import org.jpmml.converter.regression.RegressionModelUtil;
import org.tensorflow.Operation;
import org.tensorflow.Output;
import org.tensorflow.Tensor;
import org.tensorflow.framework.NodeDef;

public class LinearRegressor extends Estimator {

	public LinearRegressor(SavedModel savedModel){
		this(savedModel, LinearRegressor.HEAD);
	}

	public LinearRegressor(SavedModel savedModel, String head){
		super(savedModel, head);
	}

	@Override
	public RegressionModel encodeModel(TensorFlowEncoder encoder){
		SavedModel savedModel = getSavedModel();

		Label label;

		{
			DataField dataField = encoder.createDataField(FieldName.create("_target"), OpType.CONTINUOUS, DataType.FLOAT);

			label = new ContinuousLabel(dataField);
		}

		List<Feature> features = new ArrayList<>();

		List<Double> coefficients = new ArrayList<>();

		List<NodeDef> matMuls = getMatMulList();
		for(NodeDef matMul : matMuls){
			NodeDef multiplicand = getNodeDef(matMul.getInput(0));
			NodeDef multiplier = checkOp(getNodeDef(matMul.getInput(1)), "VariableV2");

			Feature feature = encodeFeature(multiplicand, encoder);

			features.add(feature);

			try(Tensor tensor = savedModel.run(multiplier.getName())){
				float value = TensorUtil.toFloatScalar(tensor);

				coefficients.add(floatToDouble(value));
			}
		}

		Double intercept;

		{
			NodeDef bias = getBias();

			try(Tensor tensor = savedModel.run(bias.getName())){
				float value = TensorUtil.toFloatScalar(tensor);

				intercept = floatToDouble(value);
			}
		}

		Schema schema = new Schema(label, features);

		RegressionModel regressionModel = new RegressionModel(MiningFunction.REGRESSION, ModelUtil.createMiningSchema(schema), null)
			.addRegressionTables(RegressionModelUtil.createRegressionTable(schema.getFeatures(), intercept, coefficients));

		return regressionModel;
	}

	@Override
	public NodeDef simplify(NodeDef nodeDef){
		SavedModel savedModel = getSavedModel();

		if(("Reshape").equals(nodeDef.getOp())){
			Operation operation = savedModel.getOperation(nodeDef.getName());

			Output output = operation.output(0);
			if(Arrays.equals(ShapeUtil.toArray(output.shape()), new long[]{-1, 1})){
				return getNodeDef(nodeDef.getInput(0));
			}
		}

		return super.simplify(nodeDef);
	}

	public NodeDef getBiasAdd(){
		return checkOp(getNodeDef(getHead()), "BiasAdd");
	}

	public List<NodeDef> getMatMulList(){
		NodeDef biasAdd = getBiasAdd();

		List<NodeDef> result = new ArrayList<>();

		NodeDef addN = checkOp(getNodeDef(biasAdd.getInput(0)), "AddN");

		List<String> inputNames = addN.getInputList();
		for(String inputName : inputNames){
			NodeDef matMul = checkOp(getNodeDef(inputName), "MatMul");

			result.add(matMul);
		}

		return result;
	}

	public NodeDef getBias(){
		NodeDef biasAdd = getBiasAdd();

		return checkOp(getNodeDef(biasAdd.getInput(1)), "VariableV2");
	}

	static
	private double floatToDouble(float value){
		return Double.parseDouble(Float.toString(value));
	}

	public static final String HEAD = "linear/regression_head/predictions/scores";
}