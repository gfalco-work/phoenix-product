import aws from 'aws-sdk';
import awsXRay from 'aws-xray-sdk';
import {DynamoDBClient} from "@aws-sdk/client-dynamodb";
import {
  DynamoDBDocumentClient,
  PutCommand,
} from "@aws-sdk/lib-dynamodb";

const client = new DynamoDBClient({});
const dynamo = DynamoDBDocumentClient.from(client);
const tableName = "ProductTable";

awsXRay.captureAWS(aws);

export async function handler(product) {
  console.log('Received Step Functions event:', JSON.stringify(product, null, 2));

  let statusCode = 200;
  let body;
  try {

    body = {
      productId: product.id
    };

    const segment = awsXRay.getSegment();
    const subSegment = segment.addNewSubsegment('PutEventInDynamoDb');
    subSegment.addAnnotation('product id', product.id);
    subSegment.addMetadata('product', product);

    await dynamo.send(
        new PutCommand({
          TableName: tableName,
          Item: {
            PK: 'PRODUCT#' + product.id,
            SK: product.category,
            name: product.name,
            description: product.description,
            price: product.price
          },
        })
    );
    console.log("Product saved");
    subSegment.close();
  } catch (err) {
    console.log(err.message);
    statusCode = 500;
  }
  const headers = {
    "Content-Type": "application/json",
    "Access-Control-Allow-Origin": "*",
    "Access-Control-Allow-Methods": "POST"
  };
  return {statusCode, body, headers};
}