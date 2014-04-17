package org.deeplearning4j.rbm;

import static org.deeplearning4j.util.MatrixUtil.*;
import static org.deeplearning4j.util.MatrixUtil.mean;
import static org.jblas.MatrixFunctions.exp;

import org.apache.commons.math3.distribution.RealDistribution;
import org.apache.commons.math3.random.RandomGenerator;
import org.deeplearning4j.berkeley.Pair;
import org.deeplearning4j.nn.Tensor;
import org.deeplearning4j.nn.gradient.NeuralNetworkGradient;
import org.deeplearning4j.util.MatrixUtil;
import org.jblas.DoubleMatrix;
import org.jblas.ranges.RangeUtils;

import static org.jblas.DoubleMatrix.zeros;

public class ConvolutionalRBM extends RBM  {

    /**
     *
     */
    private static final long serialVersionUID = 6868729665328916878L;
    private int numFilters;
    private int poolRows;
    private int poolColumns;
    //top down signal from hidden feature maps to visibles
    private Tensor visI;
    //bottom up signal from visibles to hiddens
    private Tensor hidI;
    //visible unit expectation
    private DoubleMatrix visExpectation;
    //hidden unit expectation
    private DoubleMatrix hiddenExpectation;
    //initial hidden expectation for gradients
    private DoubleMatrix initialHiddenExpectation;
    private DoubleMatrix fmSize;
    private Tensor eHid,eVis;
    private Tensor W;
    private DoubleMatrix poolingLayer;
    private int[] stride;


    protected ConvolutionalRBM() {}




    protected ConvolutionalRBM(DoubleMatrix input, int nVisible, int n_hidden, DoubleMatrix W,
                               DoubleMatrix hbias, DoubleMatrix vBias, RandomGenerator rng,double fanIn,RealDistribution dist) {
        super(input, nVisible, n_hidden, W, hbias, vBias, rng,fanIn,dist);
    }


    public DoubleMatrix visibleExpectation(DoubleMatrix visible,double bias) {
        DoubleMatrix filterMatrix = new DoubleMatrix(numFilters);
        for(int k = 0; k < numFilters; k++) {
            DoubleMatrix next = MatrixUtil.convolution2D(visible,
                    visible.columns,
                    visible.rows, this.getW().getRow(k), this.getW().rows, this.getW().columns).add(this.getvBias().add(bias)).transpose();
            filterMatrix.putRow(k,next);
        }

        //   filterMatrix = pool(filterMatrix);

        filterMatrix.addi(1);
        filterMatrix = MatrixUtil.oneDiv(filterMatrix);

        //replace with actual function later, sigmoid is only one possible option
        return MatrixUtil.sigmoid(filterMatrix);

    }


    private void init() {
        W = new Tensor(getnVisible(),getnHidden(),numFilters);
        visI = Tensor.zeros(getnVisible(),getnHidden(),numFilters);
        hidI = Tensor.zeros(getnVisible() - numFilters + 1,getnHidden() - numFilters + 1,numFilters);
    }



    public DoubleMatrix pooledExpectation(DoubleMatrix visible,double bias) {
        DoubleMatrix filterMatrix = new DoubleMatrix(numFilters);
        for(int k = 0; k < numFilters; k++) {
            DoubleMatrix next = MatrixUtil.convolution2D(visible,
                    visible.columns,
                    visible.rows, this.getW().getRow(k), this.getW().rows, this.getW().columns).add(this.gethBias().add(bias)).transpose();
            filterMatrix.putRow(k,next);
        }

        // filterMatrix = pooledExpectation(filterMatrix);

        filterMatrix.addi(1);
        filterMatrix = MatrixUtil.oneDiv(filterMatrix);

        return filterMatrix;

    }

    /**
     * Calculates the activation of the visible :
     * sigmoid(v * W + hbias)
     *
     * @param v the visible layer
     * @return the approximated activations of the visible layer
     */
    @Override
    public DoubleMatrix propUp(DoubleMatrix v) {
        for(int i = 0; i < numFilters; i++) {
            hidI.setSlice(i,convolution2D(v,MatrixUtil.reverse(W.getSlice(i)).add(hBias.get(i))));
        }
        DoubleMatrix expHidI = exp(hidI);

        DoubleMatrix eHid = exp(hidI).div(pooledExpectation(expHidI, hBias.get(0)));
        return eHid;
    }

    /**
     * Calculates the activation of the hidden:
     * sigmoid(h * W + vbias)
     *
     * @param h the hidden layer
     * @return the approximated output of the hidden layer
     */
    @Override
    public DoubleMatrix propDown(DoubleMatrix h) {
        Tensor h1 = (Tensor) h;
        for(int i = 0; i < numFilters; i++) {
            visI.setSlice(i,
                    convolution2D(h1.getSlice(i),W.getSlice(i)));
        }

        DoubleMatrix I = visI.sliceElementSums().addRowVector(vBias);

        return sigmoid(I);
    }


    public Tensor poolGivenVisible(Tensor input) {
        Tensor I = Tensor.zeros(eHid.rows(),eHid.columns(),eHid.slices());
        for(int i = 0;i  < numFilters; i++) {
            I.setSlice(i,convolution2D(input,MatrixUtil.reverse(W.getSlice(i))));
        }

        return I;
    }


    public Tensor pool(Tensor input) {
        int nCols = input.columns();
        int rows = input.rows();
        Tensor ret = Tensor.zeros(rows,nCols,input.slices());
        for(int i = 0;i < Math.ceil(nCols / stride[0]); i++) {
            int rowsMin = (i - 1) * stride[0] + 1;
            int rowsMax = i * stride[0];
            for(int j = 0; j < Math.ceil(nCols / stride[1]); j++) {
                int cols = (j - 1) * stride[1] + 1;
                int colsMax = j * stride[1];
                Tensor blockVal =  null;
                int rowLength = rowsMax - rowsMin;
                int colLength = colsMax - cols;

                Tensor set = blockVal.permute(new int[]{2,3,1}).repmat(rowLength,colLength);
                ret.put(RangeUtils.interval(rowsMin, rowsMax), RangeUtils.interval(cols, colsMax), set);



            }

        }
        return ret;
    }


    /**
     * Binomial sampling of the hidden values given visible
     *
     * @param v the visible values
     * @return a binomial distribution containing the expected values and the samples
     */
    @Override
    public Pair<DoubleMatrix, DoubleMatrix> sampleHiddenGivenVisible(DoubleMatrix v) {
        return super.sampleHiddenGivenVisible(v);
    }

    @Override
    public NeuralNetworkGradient getGradient(Object[] params) {
        int k = (int) params[0];
        double learningRate = (double) params[1];


        if(wAdaGrad != null)
            wAdaGrad.setMasterStepSize(learningRate);
        if(hBiasAdaGrad != null )
            hBiasAdaGrad.setMasterStepSize(learningRate);
        if(vBiasAdaGrad != null)
            vBiasAdaGrad.setMasterStepSize(learningRate);

		/*
		 * Cost and updates dictionary.
		 * This is the update rules for weights and biases
		 */
        Pair<DoubleMatrix,DoubleMatrix> probHidden = sampleHiddenGivenVisible(input);

		/*
		 * Start the gibbs sampling.
		 */
        DoubleMatrix chainStart = probHidden.getSecond();

		/*
		 * Note that at a later date, we can explore alternative methods of
		 * storing the chain transitions for different kinds of sampling
		 * and exploring the search space.
		 */
        Pair<Pair<DoubleMatrix,DoubleMatrix>,Pair<DoubleMatrix,DoubleMatrix>> matrices = null;
        //negative visible means or expected values
        DoubleMatrix nvMeans = null;
        //negative value samples
        DoubleMatrix nvSamples = null;
        //negative hidden means or expected values
        DoubleMatrix nhMeans = null;
        //negative hidden samples
        DoubleMatrix nhSamples = null;

		/*
		 * K steps of gibbs sampling. This is the positive phase of contrastive divergence.
		 *
		 * There are 4 matrices being computed for each gibbs sampling.
		 * The samples from both the positive and negative phases and their expected values
		 * or averages.
		 *
		 */

        for(int i = 0; i < k; i++) {


            if(i == 0)
                matrices = gibbhVh(chainStart);
            else
                matrices = gibbhVh(nhSamples);

            //get the cost updates for sampling in the chain after k iterations
            nvMeans = matrices.getFirst().getFirst();
            nvSamples = matrices.getFirst().getSecond();
            nhMeans = matrices.getSecond().getFirst();
            nhSamples = matrices.getSecond().getSecond();
        }

		/*
		 * Update gradient parameters
		 */
        DoubleMatrix wGradient = input.transpose().mmul(probHidden.getSecond()).sub(
                nvSamples.transpose().mmul(nhMeans)
        );



        DoubleMatrix hBiasGradient = null;

        if(sparsity != 0)
            //all hidden units must stay around this number
            hBiasGradient = mean(scalarMinus(sparsity,probHidden.getSecond()),0);
        else
            //update rule: the expected values of the hidden input - the negative hidden  means adjusted by the learning rate
            hBiasGradient = mean(probHidden.getSecond().sub(nhMeans), 0);




        //update rule: the expected values of the input - the negative samples adjusted by the learning rate
        DoubleMatrix  vBiasGradient = mean(input.sub(nvSamples), 0);
        NeuralNetworkGradient ret = new NeuralNetworkGradient(wGradient, vBiasGradient, hBiasGradient);

        updateGradientAccordingToParams(ret, learningRate);
        triggerGradientEvents(ret);

        return ret;
    }



    /**
     * Guess the visible values given the hidden
     *
     * @param h
     * @return
     */
    @Override
    public Pair<DoubleMatrix, DoubleMatrix> sampleVisibleGivenHidden(DoubleMatrix h) {
        for(int i = 0;i < numFilters; i++) {

        }

        return super.sampleVisibleGivenHidden(h);
    }


}