# Copyright (c) 2010-2012 Sebastian Bauer
#
# All rights reserved.
#
# Redistribution and use in source and binary forms, with or without
# modification, are permitted (subject to the limitations in the
# disclaimer below) provided that the following conditions are met:
#
# * Redistributions of source code must retain the above copyright
#   notice, this list of conditions and the following disclaimer.
#
# * Redistributions in binary form must reproduce the above copyright
#   notice, this list of conditions and the following disclaimer in the
#   documentation and/or other materials provided with the
#   distribution.
#
# * Neither the name of Sebastian Bauer nor the names of its
#   contributors may be used to endorse or promote products derived
#   from this software without specific prior written permission.
#
# NO EXPRESS OR IMPLIED LICENSES TO ANY PARTY'S PATENT RIGHTS ARE
# GRANTED BY THIS LICENSE.  THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT
# HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED
# WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
# MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
# DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
# LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
# CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
# SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
# BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
# WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
# OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
# IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

library(compiler)
library(parallel)

VERBOSE<-F

#' Evaluates a given data frame for classification performance
#' 
#' @param d represents a frame, from which data is gathered
#' @param v represents a column matrix, in which the name of the slots 
#'        that are used for the plots of the data frame can be specified. The last
#'        column represents whether high values are good or not.
evaluate.def<-function(d,v)
{
	if (nrow(d)==0)
	{
		return()
	}
	
	colnames(v)<-c("short","full","high.is.good")

  	eval.single<-function(i)
  	{
    	# get the type
    	type<-v[i,1]
    
    	if (VERBOSE) {message(type);}
    
    	# get primary and optional secondary values
    	if (type == "resnik.avg.p.opt")
    	{
    	  primary.values<-d$resnik.avg.p
    	  secondary.values<-1-d$label
    	} else
    	{
      	primary.values<-d[,type]
      
      	if (type == "resnik.avg.p")
      	{
      	  secondary.values<- -d$resnik.avg
      	} else if (type == "lin.avg.p")
      	{
      	  secondary.values<- -d$lin.avg
      	} else if (type == "jc.avg.p")
      	{
      	  secondary.values<- -d$jc.avg.p
      	} else
      	{
        	secondary.values<-NA
    	  }
    	}
    
    	# get the order
    	if (is.na(secondary.values[1]))
    	{
    	  ord<-order(primary.values,decreasing=as.logical(v[i,3]))
    	} else
    	{
    	  ord<-order(primary.values,secondary.values,decreasing=as.logical(v[i,3]))
    	}
    
    	# data is ordered. Threshold is such that the values
    	# above an element are flagged as positive (inclusive)
    	# and values below an element as negative.
    	values<-primary.values[ord]
    	labels<-d[ord,]$label
    
    	tps<-cumsum(labels)					# true positives
    	fps<-(1:length(labels)) - tps		# false positives
    
    	tpr<-tps / tps[length(tps)]			# true positives rate
    	fpr<-fps / fps[length(fps)]			# false positives rate
    	prec<-tps/(1:length(values))		# number of true positives / (number of all positives = (true positives + false negatives))
    	recall<-tps/sum(labels)      		# number of true positives / (true positives + false negatives = all positive samples)
    
    	l<-list(name=v[i,2],short=v[i,1])
    
    	# precision/recall values
    	idx.dots<-cumsum(hist(recall,plot=F,breaks=15)$counts)
    	idx.lines<-cumsum(hist(recall,plot=F,breaks=300)$counts)
    	l<-c(l,prec.lines=list(prec[idx.lines]),recall.lines=list(recall[idx.lines]))
    	l<-c(l,prec.dots =list(prec[idx.dots]), recall.dots =list(recall[idx.dots]))
    
    	#
    	# true positive / false postive
    	#
    	idx.dots<-cumsum(hist(fpr,plot=F,breaks=25)$counts)
    	idx.lines<-c(1,cumsum(hist(fpr,plot=F,breaks=300)$counts))
    
    	# For AUROC scores we request a higher resolution 
    	idx.auroc<-c(1,cumsum(hist(fpr,plot=F,breaks=1000)$counts))
    
    	# calculate the AUROC. Note that diff() returns the difference of
    	# consecutive elements. We calculate the lower bound of the area.
    	auroc<-sum(c(diff(fpr[idx.lines]),0) * tpr[idx.lines])
    
    	l<-c(l,fpr.lines=list(fpr[idx.lines]), tpr.lines=list(tpr[idx.lines]))
    	l<-c(l,fpr.dots= list(fpr[idx.dots]),  tpr.dots= list(tpr[idx.dots]))
    	l<-c(l,auroc=auroc)
    	return(l)
	}

	res<-mclapply(1:nrow(v),eval.single,mc.cores=min(4,detectCores()));
	return(res)
}

evaluate<-cmpfun(evaluate.def)

v<-matrix(c("marg","BN", T,
	"marg.ideal", "BN'", T,
	"marg.freq","FABN", T,
	"marg.freq.ideal", "FABN'", T,
	"resnik.avg", "Resnik",T,
	"resnik.avg.rank", "Resnik (rank)",F,
	"resnik.avg.p", "Resnik P",F,
	"resnik.avg.p.opt", "Resnik P*",F,
	"lin.avg", "Lin", T,
	"lin.avg.p", "Lin P", F,
	"jc.avg", "JC",T,
	"jc.avg.p", "JC P", F,
	"mb", "MB", T),ncol=3,byrow=T)

boqa.name.robj<-paste(boqa.base.name,"RObj",sep=".")
boqa.name.result.robj<-paste(boqa.base.name,"_result.RObj",sep="")

# only freq vs freq
if ((!file.exists(boqa.name.robj)) || (file.info(boqa.name.robj)$mtime < file.info(boqa.name)$mtime))
{
	message("Loading data");
	d<-boqa.load.data()

	d<-d[order(d$run),]

	resnik.avg.rank<-unlist(tapply(d$resnik.avg,d$run,function(x) {r<-rank(x);return (max(r) - r + 1)}))
	d<-cbind(d,resnik.avg.rank)
		
	marg.ideal.rank<-unlist(tapply(d$marg.ideal,d$run,function(x) {r<-rank(x);return (max(r) - r + 1)}))
	d<-cbind(d,marg.ideal.rank)
		
	marg.rank<-unlist(tapply(d$marg,d$run,function(x) {r<-rank(x);return (max(r) - r + 1)}))
	d<-cbind(d,marg.rank)
		
	marg.freq.rank<-unlist(tapply(d$marg.freq,d$run,function(x) {r<-rank(x);return (max(r) - r + 1)}))
	d<-cbind(d,marg.freq.rank)
		
	marg.freq.ideal.rank<-unlist(tapply(d$marg.freq.ideal,d$run,function(x) {r<-rank(x);return (max(r) - r + 1)}))
	d<-cbind(d,marg.freq.ideal.rank)
		
	resnik.avg.p.rank <- unlist(tapply(d$resnik.avg.p,d$run,function(x) {r<-rank(-x);return (max(r) - r + 1)})) 
	d<-cbind(d,resnik.avg.p.rank)
		
	f<-data.frame(p=d$resnik.avg.p,s=d$resnik.avg)
	resnik.avg.p.rank<-unsplit(lapply(split(f,d$run),function (x)
	{
		o<-order(x$p,-x$s)
		r<-1:length(o)
		r[o]<-1:length(o)
		return (r)
	}),d$run)
	d<-cbind(d,resnik.avg.p.rank)

	save(d,file=boqa.name.robj);
	message("Data loaded, preprocessed, and stored");
} else
{
	load(boqa.name.robj)
	message("Data loaded from storage")
}

# Calculate avg ranks
d.label.idx<-which(d$label==1)

message("Evaluating")

res.list<-evaluate(d,v)
save(res.list,file=boqa.name.result.robj,compress=T)

# values for the table (freq)
freq.important<-which(d$marg.freq > 0.5)
freq.positives<-length(freq.important)
freq.tp<-sum(d$label[freq.important])
print(sprintf("tp=%d tp+fp=%d ppv=%g",freq.tp,freq.positives,freq.tp/freq.positives))

freq.ideal.important<-which(d$marg.freq.ideal > 0.5)
freq.ideal.positives<-length(freq.ideal.important)
freq.ideal.tp<-sum(d$label[freq.ideal.important])
print(sprintf("tp=%d tp+fp=%d ppv=%g",freq.ideal.tp,freq.ideal.positives,freq.ideal.tp/freq.ideal.positives))

avg.p.important<-which(d$resnik.avg.p<0.05/2368)
avg.p.positives<-length(avg.p.important)
avg.p.tp<-sum(d$label[avg.p.important])
print(sprintf("tp=%d tp+fp=%d ppv=%g",avg.p.tp,avg.p.positives,avg.p.tp/avg.p.positives))

#col<-c("red","blue","cyan","green","gray","orange","magenta", "black")
col<-rainbow(length(res.list))


#
# Output basic figures. They are generated slightly different in the
# manuscript but the data is the same.
#

# Precision/Recall Plot

pdf(paste(boqa.base.name,"-precall.pdf",sep=""))

plot.new()
plot.window(xlim=c(0,1),ylim=c(0,1),xlab="Precision",ylab="Recall")
axis(1)
axis(2)
box()
for (i in 1:length(res.list))
{
	lines(res.list[[i]]$recall.lines,res.list[[i]]$prec.lines,type="l",col=col[i])
	points(res.list[[i]]$recall.dots,res.list[[i]]$prec.dots,pch=i,col=col[i])
}

legend(x="bottomleft",as.character(lapply(res.list,function(x) x$name)),col=col,lty=1,pch=1:length(res.list),cex=0.9)

dev.off()


# ROC plot

pdf(paste(boqa.base.name,"-roc.pdf",sep=""))

plot.new()
plot.window(xlim=c(0,1),ylim=c(0,1),xlab="True Positive Rate",ylab="False Positive Rate")
axis(1)
axis(2)
box()
for (i in 1:length(res.list))
{
	lines(res.list[[i]]$fpr.lines,res.list[[i]]$tpr.lines,type="l",col=col[i])
	points(res.list[[i]]$fpr.dots,res.list[[i]]$tpr.dots,pch=i,col=col[i])
}

legend(x="bottomright",as.character(lapply(res.list,function(x) x$name)),col=col,lty=1,pch=1:length(res.list),cex=0.9)

dev.off()
